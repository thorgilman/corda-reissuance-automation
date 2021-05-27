package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.AssetContract
import com.template.states.AssetState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * IssueAssetFlowInitiator is a basic flow to issue an AssetState.
 */

@InitiatingFlow
@StartableByRPC
class IssueAssetFlowInitiator(val assetType: String, val holderParty: Party) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val command = Command(AssetContract.Commands.Create(), listOf(ourIdentity, holderParty).map { it.owningKey })
        val dataState = AssetState(assetType, ourIdentity, holderParty)
        val stateAndContract = StateAndContract(dataState, AssetContract.ID)
        val txBuilder = TransactionBuilder(notary).withItems(stateAndContract, command)
        txBuilder.verify(serviceHub)

        val tx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = initiateFlow(holderParty)
        val stx = subFlow(CollectSignaturesFlow(tx, listOf(targetSession)))
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(IssueAssetFlowInitiator::class)
class IssueAssetFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {}
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
