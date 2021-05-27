package com.template

import com.r3.corda.lib.reissuance.flows.*
import com.r3.corda.lib.reissuance.states.ReissuanceLock
import com.r3.corda.lib.reissuance.states.ReissuanceRequest
import com.template.contracts.AssetContract
import com.template.flows.ExitAssetFlowInitiator
import com.template.flows.IssueAssetFlowInitiator
import com.template.flows.TransferAssetFlowInitiator
import com.template.states.AssetState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.*
import net.corda.testing.node.internal.cordappsForPackages
import net.corda.testing.node.internal.findCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlowTests {

    lateinit var mockNetwork: MockNetwork
    lateinit var issuer: StartedMockNode
    lateinit var a: StartedMockNode
    lateinit var b: StartedMockNode

    /** Basic unit testing for Reissuance CorDapp functionality (not automated)
     *  See ReissueanceAutomationDriverTest for automation tests using DriverDSL */

    @Before
    fun setup() {
        mockNetwork = MockNetwork(MockNetworkParameters(cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("com.template.contracts"),
                TestCordapp.findCordapp("com.template.flows"),
                TestCordapp.findCordapp("com.r3.corda.lib.reissuance.flows"),
                TestCordapp.findCordapp("com.r3.corda.lib.reissuance.contracts")
        )))
        issuer = mockNetwork.createNode(MockNodeParameters())
        a = mockNetwork.createNode(MockNodeParameters())
        b = mockNetwork.createNode(MockNodeParameters())
        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    @Test
    fun `happy path`() {
        // The issuer issues the AssetState to party A
        val issueTx = issuer.runFlow(IssueAssetFlowInitiator("Gold", a.identity()))
        val assetId = issueTx.tx.outRefsOfType<AssetState>()[0].state.data.linearId

        // A transfers the asset to B and then B transfers the asset back to A
        val transfer1Tx = a.runFlow(TransferAssetFlowInitiator(assetId, b.identity()))
        val transfer2Tx = b.runFlow(TransferAssetFlowInitiator(assetId, a.identity()))

        val originalAssetState = transfer2Tx.tx.outRefsOfType<AssetState>()[0].state.data
        val assetStateId = originalAssetState.linearId

        /** Step #1: Request Reissuance */
        // The requester (A) sends a reissuance request to the issuer
        val stateRefToReissue = transfer2Tx.coreTransaction.outRefsOfType<AssetState>()[0].ref
        val assetIssuanceCommand = AssetContract.Commands.Create()
        val requestReissuanceHash = a.runFlow(RequestReissuanceAndShareRequiredTransactions<AssetState>(issuer.identity(), listOf(stateRefToReissue), assetIssuanceCommand))
        val requestReissuanceTx = a.services.validatedTransactions.getTransaction(requestReissuanceHash)!!

        /** Step #2: Accepting Reissuance Request */
        // The issuer accepts the reissuance request
        // Two encumbered states are created, a reissued AssetState & an active ReIssuanceLock
        val reissueanceRequestRef = requestReissuanceTx.tx.outRefsOfType<ReissuanceRequest>()[0]
        val reissueanceTxHash = issuer.runFlow(ReissueStates<AssetState>(reissueanceRequestRef))
        val reissuanceTx = issuer.services.validatedTransactions.getTransaction(reissueanceTxHash)!!
        val newAssetStateAndRef = reissuanceTx.tx.outRefsOfType<AssetState>()[0]
        val reissuanceLockStateAndRef = reissuanceTx.tx.outRefsOfType<ReissuanceLock<AssetState>>()[0]

        /** Step #3: Exiting the Original State */
        // The requester (A) exits the original state
        val exitTx = a.runFlow(ExitAssetFlowInitiator(assetStateId))
        val exitTxHash = exitTx.tx.id

        /** Step #4: Unlock the Reissued State */
        // The requester (A) unlocks the reissued states
        // Both states now become unemcumbered and the ReIssuanceLock is set to inactive
        // The original state's exit transaction is added as an attachment to the unlock transaction
        val unlockTxHash = a.runFlow(UnlockReissuedStates(listOf(newAssetStateAndRef), reissuanceLockStateAndRef, listOf(exitTxHash), AssetContract.Commands.Transfer())) // TODO: Transfer
        val unlockTx = a.services.validatedTransactions.getTransaction(unlockTxHash)!!
        val reissuedAssetState = unlockTx.tx.outRefsOfType<AssetState>()[0].state.data

        assertEquals(originalAssetState, reissuedAssetState)
    }

    @Test
    fun `unhappy path`() {
        // The issuer issues the AssetState to party a
        val issueTx = issuer.runFlow(IssueAssetFlowInitiator("Gold", a.identity()))
        val assetId = issueTx.tx.outRefsOfType<AssetState>()[0].state.data.linearId

        // A transfers the asset to B and then B transfers the asset back to A
        val transfer1Tx = a.runFlow(TransferAssetFlowInitiator(assetId, b.identity()))
        val transfer2Tx = b.runFlow(TransferAssetFlowInitiator(assetId, a.identity()))

        val originalAssetState = transfer2Tx.tx.outRefsOfType<AssetState>()[0].state.data
        val assetStateId = originalAssetState.linearId

        val stateRefToReissue = transfer2Tx.coreTransaction.outRefsOfType<AssetState>()[0].ref
        val assetIssuanceCommand = AssetContract.Commands.Create()

        /** Step #1: Request Reissuance */
        // The requester (A) sends a reissuance request to the issuer
        val requestReissuanceHash = a.runFlow(RequestReissuanceAndShareRequiredTransactions<AssetState>(issuer.identity(), listOf(stateRefToReissue), assetIssuanceCommand))
        val requestReissuanceTx = a.services.validatedTransactions.getTransaction(requestReissuanceHash)!!

        /** Step #2: Accepting Reissuance Request */
        // The issuer accepts the reissuance request
        // Two encumbered states are created, a reissued AssetState & an active ReIssuanceLock
        val reissueanceRequestRef = requestReissuanceTx.tx.outRefsOfType<ReissuanceRequest>()[0]
        val reissueanceTxHash = issuer.runFlow(ReissueStates<AssetState>(reissueanceRequestRef))
        val reissuanceTx = issuer.services.validatedTransactions.getTransaction(reissueanceTxHash)!!
        val newAssetStateAndRef = reissuanceTx.tx.outRefsOfType<AssetState>()[0]
        val reissuanceLockStateAndRef = reissuanceTx.tx.outRefsOfType<ReissuanceLock<AssetState>>()[0]

        /** Step #3: Consuming the Original State */
        // The requester (A) consumes the original states instead of exiting
        val exitTx = a.runFlow(TransferAssetFlowInitiator(assetId, b.identity()))
        val exitTxHash = exitTx.tx.id

        /** Step #4: (Attempt) to Unlock the Reissued State */
        // The requester (A) can attempt to unlock the reissued states but this will fail with a TransactionVerificationException
        // This occurs because the original state was consumed instead of exited
        assertFailsWith<TransactionVerificationException> {
            a.runFlow(UnlockReissuedStates(listOf(newAssetStateAndRef), reissuanceLockStateAndRef, listOf(exitTxHash), AssetContract.Commands.Transfer()))
        }

        /** Step #5: Exit the reissued states */
        // At this point the requester (A) has no choice but to exit the reissued states if it wants to reattempt the reissuance
        a.runFlow(DeleteReissuedStatesAndLock(reissuanceLockStateAndRef, listOf(newAssetStateAndRef), AssetContract.Commands.Exit()))
    }

    fun StartedMockNode.identity(): Party {
        return this.info.legalIdentities[0]
    }

    inline fun <reified T: ContractState> StartedMockNode.getStates(): List<ContractState> {
        return services.vaultService.queryBy<T>().states.map{it.state.data}
    }

    inline fun <reified T> StartedMockNode.runFlow(flowLogic: FlowLogic<T>): T {
        val future = this.startFlow(flowLogic)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

}