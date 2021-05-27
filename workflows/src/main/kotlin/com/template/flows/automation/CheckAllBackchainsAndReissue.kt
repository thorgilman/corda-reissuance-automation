package com.template.flows.automation

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.reissuance.flows.RequestReissuanceAndShareRequiredTransactions
import com.r3.corda.lib.tokens.workflows.utilities.getLinearStateById
import com.template.states.AssetState
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.identity.AbstractParty

/**
 *
 */


@StartableByRPC
class CheckAllBackchainsAndReissue: FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val reissuanceAutomationService = serviceHub.cordaService(ReissuanceAutomationService::class.java)
        reissuanceAutomationService.checkAllBackchains()
    }
}