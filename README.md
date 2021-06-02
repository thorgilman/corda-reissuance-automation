<p align="center">
  <img src="https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png" alt="Corda" width="500">
</p>

# Reissuance CorDapp Automation

This CorDapp aims to automate the Reissuance CorDapp through the use of @CordaServices.
See ReissuanceAutomationDriverTest in the integrationTest module for unit tests.
The CorDapp can also be tested manually using the flows below.


    Issuer:
        flow start IssueAssetFlowInitiator assetType: "Gold", holderParty: "PartyA"
    PartyA:
        run vaultQuery contractStateType: com.template.states.AssetState
        (Get AssetState LinearId for next step)

    PartyA:
        flow start TransferAssetFlowInitiator linearId: "", newHolderParty: "PartyB"
    PartyB:
        flow start TransferAssetFlowInitiator linearId: "", newHolderParty: "PartyA"
    PartyA:
        flow start TransferAssetFlowInitiator linearId: "", newHolderParty: "PartyB"
    PartyB:
        flow start TransferAssetFlowInitiator linearId: "", newHolderParty: "PartyA"

    PartyA:
        run vaultQuery contractStateType: com.template.states.AssetState
        (We will compare this data to the new data after running the reissuance)

        flow start CheckBackchainAndRequestReissuance id: ""
        or ...
        flow start CheckAllBackchainsAndReissue
        (Wait a few seconds)

        run vaultQuery contractStateType: com.template.states.AssetState
        (The data should remain the same but the tx hash should be different)

        run vaultQuery contractStateType: com.r3.corda.lib.reissuance.states.ReissuanceLock
        (A ReissuanceLock will have been created and should be INACTIVE)