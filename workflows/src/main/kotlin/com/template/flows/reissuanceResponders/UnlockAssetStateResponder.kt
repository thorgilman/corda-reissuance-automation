package com.template.flows.reissuanceResponders

import com.r3.corda.lib.reissuance.flows.UnlockReissuedStates
import com.r3.corda.lib.reissuance.flows.UnlockReissuedStatesResponder
import com.template.states.AssetState
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.transactions.SignedTransaction

/**
 * UnlockAssetStateResponder is a basic implementation of UnlockReissuedStatesResponder.
 * Overriding UnlockReissuedStatesResponder is required for the Reissuance CorDapp.
 */

@InitiatedBy(UnlockReissuedStates::class)
class UnlockAssetStateResponder(
        otherSession: FlowSession
) : UnlockReissuedStatesResponder(otherSession) {

    override fun checkConstraints(stx: SignedTransaction) {
        requireThat {
            otherInputs.forEach {
                val state = it.state.data as? AssetState
                "Input $it is of type AssetState" using (state != null)
            }
            otherOutputs.forEach {
                val state = it.data as? AssetState
                "Input $it is of type AssetState" using (state != null)
            }
        }
    }
}
