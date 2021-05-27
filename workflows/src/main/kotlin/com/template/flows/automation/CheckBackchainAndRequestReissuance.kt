package com.template.flows.automation

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.workflows.utilities.getLinearStateById
import com.template.states.AssetState
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC

@StartableByRPC
class CheckBackchainAndRequestReissuance(val id: UniqueIdentifier) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val reissuanceAutomationService = serviceHub.cordaService(ReissuanceAutomationService::class.java)
        val stateAndRef = serviceHub.vaultService.getLinearStateById<AssetState>(id)!!
        reissuanceAutomationService.checkBackchainAndReissue(stateAndRef)
    }
}