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
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.*
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.getOrThrow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * ReissuanceAutomationService is a @CordaService used to automate the functionality of the Reissuance CorDapp
*/

// TODO: Need to provide the following { StateClassName, ExitCommand, ExitFlow, UpdateCommand }
// The requester will always provide this
// What about multiple types of States?

// When state_machine_started get the values from the config
// Set the class as a generic type for the CordaService?

// Or no config, we set this manually using a flow


@CordaService
class ReissuanceAutomationService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    val T = AssetState::class.java


    private companion object {
        val executor: Executor = Executors.newFixedThreadPool(8)!!
        const val MAX_BACKCHAIN_LENGTH = 3 // TODO: Make configurable by CorDapp config file

        val STATE_CLASS_NAME = AssetState::class.java
        val EXIT_COMMAND = AssetContract.Commands.Exit()
        val UPDATE_COMMAND = AssetContract.Commands.Transfer()
    }

    init { serviceHub.register { processEvent(it) } }

    private fun processEvent(event: ServiceLifecycleEvent) {
        when (event) {
            ServiceLifecycleEvent.STATE_MACHINE_STARTED -> {
                // TODO: Get data from config?
                startTrackingStates()
            }
        }
    }

    @Suspendable
    fun <T> countBackchain(stateAndRef: StateAndRef<Companion.STATE_CLASS_NAME.javaClass>): Int {
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
        QueryCriteria.VaultCustomQueryCriteria(status=Vault.StateStatus.UNCONSUMED)
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
    fun startTrackingStates(classType: Class<T>) {
        println("Starting to track states...")

        // Track new ReissuanceRequests (Issuer)
        serviceHub.vaultService.trackBy<ReissuanceRequest>().updates.subscribe { update: Vault.Update<ReissuanceRequest> ->
            update.produced.forEach { reissuanceRequestStateAndRef: StateAndRef<ReissuanceRequest> ->
                val reissuanceRequestState = reissuanceRequestStateAndRef.state.data

                if (serviceHub.myInfo.isLegalIdentity(reissuanceRequestState.issuer.nameOrNull()!!)) { // TODO: Specify Issuer by CorDapp config file
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
                if (reissuanceLock.status == ReissuanceLock.ReissuanceLockStatus.ACTIVE && serviceHub.myInfo.isLegalIdentity(reissuanceLock.requester.nameOrNull()!!)) { // TODO: Specify Issuer by CorDapp config file
                    val newAssetStateAndRef = serviceHub.validatedTransactions.getTransaction(reissuanceLockStateAndRef.ref.txhash)!!.coreTransaction.outRefsOfType<AssetState>()[0]
                    executor.execute {
                        // First exit all relevant states
                        val exitHashList = mutableListOf<SecureHash>()
                        for (stateAndRef in reissuanceLock.originalStates) {
                            val flowHandle = serviceHub.startFlow(ExitAssetFlowInitiator(stateAndRef.state.data.linearId))
                            val exitTx = flowHandle.returnValue.toCompletableFuture().getOrThrow()!!
                            exitHashList.add(exitTx.tx.id)
                            println("Requester exited the old state!")
                        }
                        // Unlock the Reissued States
                        val flowHandle = serviceHub.startFlow(UnlockReissuedStatesWrapper(listOf(newAssetStateAndRef), reissuanceLockStateAndRef, exitHashList, AssetContract.Commands.Transfer()))
                        flowHandle.returnValue.toCompletableFuture().getOrThrow()
                        println("Requester reissued the new state!")
                    }
                }
            }
        }

    }
}