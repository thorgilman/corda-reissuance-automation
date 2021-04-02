package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.AssetContract
import com.template.states.AssetState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class ExitAssetFlowInitiator(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {

        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val stateRef = serviceHub.vaultService.queryBy(AssetState::class.java, QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))).states[0]
        val state = stateRef.state.data

        val command = Command(AssetContract.Commands.Exit(), listOf(ourIdentity, state.issuer).map { it.owningKey })
        val txBuilder = TransactionBuilder(notary)
        txBuilder.addInputState(stateRef)
        txBuilder.addCommand(command)

        txBuilder.verify(serviceHub)

        val tx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = initiateFlow(state.issuer)
        val stx = subFlow(CollectSignaturesFlow(tx, listOf(targetSession)))
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(ExitAssetFlowInitiator::class)
class ExitAssetFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {}
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
