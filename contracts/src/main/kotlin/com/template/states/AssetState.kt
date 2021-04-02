package com.template.states

import com.template.contracts.AssetContract
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

// *********
// * State *
// *********
@BelongsToContract(AssetContract::class)
data class AssetState(val data: String,
                      val issuer: Party,
                      val owner: Party,
                      override val linearId: UniqueIdentifier = UniqueIdentifier(),
                      override val participants: List<AbstractParty> = listOf(owner)) : ContractState, LinearState {

    fun withNewOwner(newOwner: Party): AssetState {
        return copy(owner = newOwner, participants = listOf(newOwner))
    }

    override fun equals(other: Any?): Boolean {
        when (other) {
            is AssetState -> {
                return this.data == other.data &&
                        this.issuer == other.issuer &&
                        this.owner == other.owner &&
                        this.linearId == other.linearId &&
                        this.participants == other.participants
            }
            else -> return false
        }
    }
}

