package com.template.flows.automation

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.reissuance.states.ReissuanceLock
import com.r3.corda.lib.reissuance.states.ReissuanceRequest
import com.template.contracts.AssetContract
import com.template.flows.ExitAssetFlowInitiator
import com.template.flows.wrappers.ReissueStatesWrapper
import com.template.flows.wrappers.RequestReissuanceAndShareRequiredTransactionsWrapper
import com.template.flows.wrappers.UnlockReissuedStatesWrapper
import com.template.states.AssetState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.*
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.getOrThrow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * ReissuanceAutomationService is a @CordaService used to automate the functionality of the Reissuance CorDapp
*/

@CordaService
class ReissuanceAutomationService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    private companion object {
        val executor: Executor = Executors.newFixedThreadPool(8)!!
        val MAX_BACKCHAIN_LENGTH = 3
    }

    init { serviceHub.register { processEvent(it) } }

    private fun processEvent(event: ServiceLifecycleEvent) {
        when (event) {
            ServiceLifecycleEvent.STATE_MACHINE_STARTED -> {
                startTrackingStates()
            }
        }
    }

    @Suspendable
    fun countBackchain(stateAndRef: StateAndRef<AssetState>): Int {
        var count = 0
        var tx = serviceHub.validatedTransactions.getTransaction(stateAndRef.ref.txhash)!!
        while (tx.inputs.isNotEmpty()) {
            tx = serviceHub.validatedTransactions.getTransaction(tx.tx.inputs[0].txhash)!!
            count++
        }
        return count
    }

    @Suspendable
    fun checkAllBackchains() {
        val allStates = serviceHub.vaultService.queryBy<AssetState>().states
        for (stateAndRef in allStates) {
            checkBackchainAndReissue(stateAndRef)
        }
    }

    @Suspendable
    fun checkBackchainAndReissue(stateAndRef: StateAndRef<AssetState>) {
        val backchainLength = countBackchain(stateAndRef)
        if (backchainLength > MAX_BACKCHAIN_LENGTH) {
            println(String.format("%s is requesting reissuance for %s", serviceHub.myInfo.legalIdentities[0], stateAndRef.state.data.linearId))
            executor.execute {
                val flowHandle = serviceHub.startFlow(RequestReissuanceAndShareRequiredTransactionsWrapper<AssetState>(stateAndRef.state.data.issuer, listOf(stateAndRef.ref), AssetContract.Commands.Exit()))
                flowHandle.returnValue.toCompletableFuture().getOrThrow()
            }
        }
        else println(String.format("%s has a backchain length of %d and will not be reissued", stateAndRef.state.data.linearId, backchainLength))
    }

    @Suspendable
    fun startTrackingStates() {
        println("Starting to track states...")

        // Track new ReissuanceRequests (Issuer)
        serviceHub.vaultService.trackBy<ReissuanceRequest>().updates.subscribe { update: Vault.Update<ReissuanceRequest> ->
            update.produced.forEach { reissuanceRequestStateAndRef: StateAndRef<ReissuanceRequest> ->
                val reissuanceRequestState = reissuanceRequestStateAndRef.state.data

                if (serviceHub.myInfo.isLegalIdentity(reissuanceRequestState.issuer.nameOrNull()!!)) { // TODO: Fix
                    println(serviceHub.myInfo.legalIdentities[0].name.toString() + " found a ReissuanceRequest with requester " + reissuanceRequestState.requester)
                    executor.execute {
                        val flowHandle = serviceHub.startFlow(ReissueStatesWrapper<AssetState>(reissuanceRequestStateAndRef))
                        flowHandle.returnValue.toCompletableFuture().getOrThrow()
                    }
                    println("Issuer ran ReissueStates!")
                }
            }
        }

        // Track new ReissuanceLocks (Requester)
        serviceHub.vaultService.trackBy<ReissuanceLock<AssetState>>().updates.subscribe { update: Vault.Update<ReissuanceLock<AssetState>> ->
            update.produced.forEach { reissuanceLockStateAndRef: StateAndRef<ReissuanceLock<AssetState>> ->
                val reissuanceLock = reissuanceLockStateAndRef.state.data
                if (reissuanceLock.status == ReissuanceLock.ReissuanceLockStatus.ACTIVE && serviceHub.myInfo.isLegalIdentity(reissuanceLock.requester.nameOrNull()!!)) {
                    val newAssetStateAndRef = serviceHub.validatedTransactions.getTransaction(reissuanceLockStateAndRef.ref.txhash)!!.coreTransaction.outRefsOfType<AssetState>()[0]
                    executor.execute {
                        // First exit all relevant states
                        val exitHashList = mutableListOf<SecureHash>()
                        for (stateAndRef in reissuanceLock.originalStates) {
                            val flowHandle = serviceHub.startFlow(ExitAssetFlowInitiator(stateAndRef.state.data.linearId))
                            val exitTx = flowHandle.returnValue.toCompletableFuture().getOrThrow()!!
                            exitHashList.add(exitTx.tx.id)
                        }
                        // Unlock the Reissued States
                        val flowHandle = serviceHub.startFlow(UnlockReissuedStatesWrapper(listOf(newAssetStateAndRef), reissuanceLockStateAndRef, exitHashList, AssetContract.Commands.Transfer()))
                        val txHash = flowHandle.returnValue.toCompletableFuture().getOrThrow()!!
                    }
                }
            }
        }

    }
}