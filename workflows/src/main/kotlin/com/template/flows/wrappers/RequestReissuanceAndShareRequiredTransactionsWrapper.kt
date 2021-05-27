package com.template.flows.wrappers

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.reissuance.flows.RequestReissuance
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.node.StatesToRecord

/**
 * RequestReissuanceAndShareRequiredTransactionsWrapper is a flow that allows for RequestReissuance flow to be run from a @CordaService
 * It has similar functionality to RequestReissuanceAndShareRequiredTransactions except for the fact that it will share the required transactions before requesting reissuance.
 * RequestReissuanceAndShareRequiredTransactions cannot be used because the ReissuanceRequest is created before sharing the required transactions.
 */

@InitiatingFlow
@StartableByService
class RequestReissuanceAndShareRequiredTransactionsWrapper<T>(private val issuer: AbstractParty,
                                                           private val stateRefsToReissue: List<StateRef>,
                                                           private val assetIssuanceCommand: CommandData) : FlowLogic<SecureHash>() where T: ContractState {

    @Suspendable
    override fun call(): SecureHash {
        // First share the required transactions
        val issuerSession = initiateFlow(issuer)
        val txToShare = stateRefsToReissue.map { serviceHub.validatedTransactions.getTransaction(it.txhash)!! }
        txToShare.forEach {
            subFlow(SendTransactionFlow(issuerSession, it))
        }

        // Request reissuance
        return subFlow(RequestReissuance<T>(issuer, stateRefsToReissue, assetIssuanceCommand))
    }
}

@InitiatedBy(RequestReissuanceAndShareRequiredTransactionsWrapper::class)
class ReceiveSignedTransaction(val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ReceiveTransactionFlow(
            otherSideSession = otherSession,
            statesToRecord = StatesToRecord.ALL_VISIBLE
        ))
    }
}