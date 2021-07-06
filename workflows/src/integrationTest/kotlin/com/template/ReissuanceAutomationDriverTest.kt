package com.template

import com.r3.corda.lib.reissuance.flows.GetTransactionBackChain
import com.r3.corda.lib.reissuance.states.ReissuanceLock
import com.template.flows.IssueAssetFlowInitiator
import com.template.flows.TransferAssetFlowInitiator
import com.template.flows.automation.CheckAllBackchainsAndReissue
import com.template.flows.automation.CheckBackchainAndRequestReissuance
import com.template.states.AssetState
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Test
import java.util.concurrent.Future
import kotlin.test.assertEquals

class ReissuanceAutomationDriverTest {
    private val issuerName = CordaX500Name("Issuer", "New York", "US")
    private val aName = CordaX500Name("PartyA", "New York", "US")
    private val bName = CordaX500Name("PartyB", "New York", "US")
    private lateinit var issuer: CordaRPCOps
    private lateinit var a: CordaRPCOps
    private lateinit var b: CordaRPCOps

//    @Test
//    fun `request all backchain works as expected (many)`() = withDriver {
//        issuer = setupNode(issuerName)
//        a = setupNode(aName)
//        b = setupNode(bName)
//
//        val numOfAssets = 8 // TODO: Out of Memory error at 8
//        for (i in 1..numOfAssets) {
//            setupAssetBackchain(7)
//        }
//        b.startFlow(::CheckAllBackchainsAndReissue).returnValue.toCompletableFuture().getOrThrow()
//
//        Thread.sleep(25000)
//
//        val assetStates = b.getStateAndRefs<AssetState>()
//        val reissuanceLocks = b.getStateAndRefs<ReissuanceLock<AssetState>>()
//
//        assertEquals(numOfAssets, assetStates.size)
//        assertEquals(numOfAssets, reissuanceLocks.size)
//        for (reissuanceLock in reissuanceLocks) {
//            assertEquals(ReissuanceLock.ReissuanceLockStatus.INACTIVE, reissuanceLock.state.data.status)
//        }
//    }

    @Test
    fun `request all backchain works as expected`() = withDriver {
        issuer = setupNode(issuerName)
        a = setupNode(aName)
        b = setupNode(bName)

        setupAssetBackchain(7)
        setupAssetBackchain(7)
        setupAssetBackchain(1)
        b.startFlow(::CheckAllBackchainsAndReissue).returnValue.toCompletableFuture().getOrThrow()

        Thread.sleep(10000)

        val assetStates = b.getStateAndRefs<AssetState>()
        val reissuanceLocks = b.getStateAndRefs<ReissuanceLock<AssetState>>()

        assertEquals(3, assetStates.size)
        assertEquals(2, reissuanceLocks.size)
        assertEquals(ReissuanceLock.ReissuanceLockStatus.INACTIVE, reissuanceLocks[0].state.data.status)
        assertEquals(ReissuanceLock.ReissuanceLockStatus.INACTIVE, reissuanceLocks[1].state.data.status)
    }

    @Test
    fun `requester exists state and reissues automatically`() = withDriver {
        issuer = setupNode(issuerName)
        a = setupNode(aName)
        b = setupNode(bName)

        val uniqueIdentifier = setupAssetBackchain(7)
        val stateAndRef = b.getStateByLinearId<AssetState>(uniqueIdentifier)

        val oldBackchainTransactionList = b.startFlow(::GetTransactionBackChain, stateAndRef.ref.txhash).returnValue.toCompletableFuture().getOrThrow()!!
        assertEquals(8, oldBackchainTransactionList.size)

        b.startFlow(::CheckBackchainAndRequestReissuance, uniqueIdentifier).returnValue.toCompletableFuture().getOrThrow()

        Thread.sleep(10000)

        val assetStates = b.getStateAndRefs<AssetState>()
        val reissuanceLocks = b.getStateAndRefs<ReissuanceLock<AssetState>>()

        assertEquals(1, assetStates.size)
        assertEquals(1, reissuanceLocks.size)

        assertEquals(uniqueIdentifier.id, assetStates[0].state.data.linearId.id)
        assert(stateAndRef != assetStates[0])
        assert(stateAndRef.state.data == assetStates[0].state.data)

        assertEquals(ReissuanceLock.ReissuanceLockStatus.INACTIVE, reissuanceLocks[0].state.data.status)

        val newBackchainTransactionList = b.startFlow(::GetTransactionBackChain, assetStates[0].ref.txhash).returnValue.toCompletableFuture().getOrThrow()!!
        assertEquals(3, newBackchainTransactionList.size) // (RequestReissuance TX, ReissueStates TX, UnlockReissuedStates TX)
    }

    @Test
    fun `reissuance lock is created automatically`() = withDriver {
        issuer = setupNode(issuerName)
        a = setupNode(aName)
        b = setupNode(bName)

        val uniqueIdentifier = setupAssetBackchain(7)
        val stateAndRef = b.getStateByLinearId<AssetState>(uniqueIdentifier)
        b.startFlow(::CheckBackchainAndRequestReissuance, uniqueIdentifier).returnValue.toCompletableFuture().getOrThrow()

        Thread.sleep(3000)

        val result = issuer.getStates<ReissuanceLock<AssetState>>()
        assertEquals(1, result.size)
        assertEquals(ReissuanceLock.ReissuanceLockStatus.ACTIVE, result[0].status)
    }

    fun setupAssetBackchain(length: Int): UniqueIdentifier {
        val issueTx = issuer.startFlow(::IssueAssetFlowInitiator, "Gold", a.identity()).returnValue.toCompletableFuture().getOrThrow()
        val assetId = issueTx.tx.outRefsOfType<AssetState>()[0].state.data.linearId
        for (i in 1..length) {
            if (i%2 == 1) a.startFlow(::TransferAssetFlowInitiator, assetId, b.identity()).returnValue.toCompletableFuture().getOrThrow()
            else b.startFlow(::TransferAssetFlowInitiator, assetId, a.identity()).returnValue.toCompletableFuture().getOrThrow()
        }
        return issueTx.tx.outRefsOfType<AssetState>()[0].state.data.linearId
    }

    inline fun <reified T: ContractState> CordaRPCOps.getStateAndRefs(): List<StateAndRef<T>> = this.vaultQueryBy<T>().states

    inline fun <reified T: ContractState> CordaRPCOps.getStates(): List<T> = this.vaultQueryBy<T>().states.map{it.state.data}

    inline fun <reified T: LinearState> CordaRPCOps.getStateByLinearId(linearId: UniqueIdentifier): StateAndRef<T> = this.vaultQueryBy<T>(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))).states[0]

    private fun CordaRPCOps.identity(): Party = this.nodeInfo().legalIdentities[0]

    private fun DriverDSL.setupNode(x500Name: CordaX500Name): CordaRPCOps {
        val username = x500Name.organisation
        val user = User(username, "password", permissions = setOf("ALL"))
        val handle = startNode(providedName=x500Name, rpcUsers= listOf(user)).getOrThrow()
        val client = CordaRPCClient(handle.rpcAddress)
        return client.start(username, "password").proxy
    }

    // Runs a test inside the Driver DSL, which provides useful functions for starting nodes, etc.
    private fun withDriver(test: DriverDSL.() -> Unit) = driver(
        DriverParameters(isDebug = true, startNodesInProcess = true).withExtraCordappPackagesToScan(listOf("com.r3.corda.lib.reissuance.contracts", "com.r3.corda.lib.reissuance.flows"))
    ) { test() }

    // Resolves a list of futures to a list of the promised values.
    private fun <T> List<Future<T>>.waitForAll(): List<T> = map { it.getOrThrow() }

    // Starts multiple nodes simultaneously, then waits for them all to be ready.
    private fun DriverDSL.startNodes(vararg identities: TestIdentity) = identities
        .map { startNode(providedName = it.name) }
        .waitForAll()
}