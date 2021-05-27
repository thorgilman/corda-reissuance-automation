package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.AssetContract
import com.template.states.AssetState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * TransferAssetFlowInitiator is a flow used to transfer an AssetState's ownership to a new party.
 */

@InitiatingFlow
@StartableByRPC
class TransferAssetFlowInitiator(val linearId: UniqueIdentifier, val newHolderParty: Party) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val stateRef = serviceHub.vaultService.queryBy(AssetState::class.java, QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))).states[0]
        val state = stateRef.state.data
        val newState = state.withNewOwner(newHolderParty)

        val command = Command(AssetContract.Commands.Transfer(), listOf(ourIdentity, newHolderParty).map { it.owningKey })
        val txBuilder = TransactionBuilder(notary)
        txBuilder.addInputState(stateRef)
        txBuilder.addOutputState(newState)
        txBuilder.addCommand(command)
        txBuilder.verify(serviceHub)

        val tx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = initiateFlow(newHolderParty)
        val stx = subFlow(CollectSignaturesFlow(tx, listOf(targetSession)))
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(TransferAssetFlowInitiator::class)
class TransferAssetFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {}
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
