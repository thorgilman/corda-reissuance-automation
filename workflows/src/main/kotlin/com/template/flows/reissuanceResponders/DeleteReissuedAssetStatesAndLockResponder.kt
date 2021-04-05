package com.template.flows.reissuanceResponders

import com.r3.corda.lib.reissuance.flows.DeleteReissuedStatesAndLock
import com.r3.corda.lib.reissuance.flows.DeleteReissuedStatesAndLockResponder
import com.template.states.AssetState
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.transactions.SignedTransaction

@InitiatedBy(DeleteReissuedStatesAndLock::class)
class DeleteReissuedAssetStatesAndLockResponder(
        otherSession: FlowSession
) : DeleteReissuedStatesAndLockResponder(otherSession) {
    override fun checkConstraints(stx: SignedTransaction) {
        requireThat {
            otherInputs.forEach {
                val state = it.state.data as? AssetState
                "Input $it is of type AssetState" using (state != null)
            }
        }
    }
}
