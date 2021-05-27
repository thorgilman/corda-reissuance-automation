package com.template.flows.wrappers

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.reissuance.flows.ReissueStates
import com.r3.corda.lib.reissuance.states.ReissuanceRequest
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*

/**
 * ReissueStatesWrapper is a flow that allows for the ReissueStates flow to be run from a @CordaService.
 */

@StartableByService
class ReissueStatesWrapper<T>(private val reissuanceRequestStateAndRef: StateAndRef<ReissuanceRequest>) : FlowLogic<SecureHash>() where T: ContractState {

    @Suspendable
    override fun call(): SecureHash {
        return subFlow(ReissueStates<T>(reissuanceRequestStateAndRef))
    }
}