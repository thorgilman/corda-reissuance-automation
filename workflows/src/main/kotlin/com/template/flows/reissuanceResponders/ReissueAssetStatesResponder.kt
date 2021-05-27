package com.template.flows.reissuanceResponders

import com.r3.corda.lib.reissuance.flows.ReissueStates
import com.r3.corda.lib.reissuance.flows.ReissueStatesResponder
import com.template.states.AssetState
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.transactions.SignedTransaction

/**
 * ReissueAssetStateResponder is a basic implementation of ReissueStatesResponder.
 * Overriding ReissueStatesResponder is required for the Reissuance CorDapp.
 */

@InitiatedBy(ReissueStates::class)
class ReissueAssetStateResponder(
        otherSession: FlowSession
) : ReissueStatesResponder(otherSession) {
    override fun checkConstraints(stx: SignedTransaction) {
        requireThat {
            otherOutputs.forEach {
                val state = it.data as? AssetState
                "Output $it is of type AssetState" using (state != null)
            }
        }
    }
}
