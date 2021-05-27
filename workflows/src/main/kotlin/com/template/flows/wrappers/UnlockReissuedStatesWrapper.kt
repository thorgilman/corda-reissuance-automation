package com.template.flows.wrappers

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.reissuance.flows.UnlockReissuedStates
import com.r3.corda.lib.reissuance.states.ReissuanceLock
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty

/**
 * UnlockReissuedStatesWrapper is a flow that allows for UnlockReissuedStates flow to be run from a @CordaService
 */

@StartableByService
class UnlockReissuedStatesWrapper<T>(private val reissuedStateAndRefs: List<StateAndRef<T>>,
                                     private val reissuanceLock: StateAndRef<ReissuanceLock<T>>,
                                     private val assetExitTransactionIds: List<SecureHash>,
                                     private val assetUnencumberCommand: CommandData,
                                     private val extraAssetUnencumberCommandSigners: List<AbstractParty> = listOf()
) : FlowLogic<SecureHash>() where T: ContractState {

    @Suspendable
    override fun call(): SecureHash {
        return subFlow(UnlockReissuedStates<T>(reissuedStateAndRefs, reissuanceLock, assetExitTransactionIds, assetUnencumberCommand, extraAssetUnencumberCommandSigners))
    }
}