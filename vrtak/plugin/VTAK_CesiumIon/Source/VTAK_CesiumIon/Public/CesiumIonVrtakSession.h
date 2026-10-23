#pragma once

#include "CoreMinimal.h"
#include "HttpRouteHandle.h"
#include "UObject/Object.h"
#include "CesiumIonVrtakSession.generated.h"

/** One Cesium ion asset, as /v1/assets lists it. */
USTRUCT()
struct FCesiumIonVrtakAsset
{
	GENERATED_BODY()

	int64 Id = 0;
	FString Name;
	/** ion's type: 3DTILES, IMAGERY, TERRAIN, GEOJSON, KML, CZML, ... */
	FString Type;
	FString Status;
	int64 Bytes = 0;
};

DECLARE_DELEGATE_TwoParams(FCesiumIonVrtakAssetsResult, bool /*bOk*/, const FString& /*Message*/);
DECLARE_DELEGATE_TwoParams(FCesiumIonVrtakSignInResult, bool /*bOk*/, const FString& /*Message*/);

/**
 * Connection to Cesium ion Cloud or a Self-Hosted server: settings, the
 * saved access token (DPAPI-encrypted for the Windows user, never logged),
 * the asset list, and "Sign in with Cesium ion" (OAuth 2.0 authorization code
 * with PKCE, the same public client as the ATAK plugin).
 *
 * Settings: %ProjectSaved%/CesiumIon/settings.json, or for tests the command
 * line -CesiumIonServer=<url> [-CesiumIonApi=<url>] for a single-user
 * Self-Hosted server.
 */
UCLASS()
class UCesiumIonVrtakSession : public UObject
{
	GENERATED_BODY()

public:
	void Load();

	bool IsSelfHosted() const { return bSelfHosted; }
	/** A single-user Self-Hosted server needs no sign-in. */
	bool NeedsToken() const { return !(bSelfHosted && bSingleUser); }
	bool HasToken() const { return !Token.IsEmpty(); }
	bool IsReady() const { return !NeedsToken() || HasToken(); }
	bool IsSigningIn() const { return bSigningIn; }
	FString Describe() const;
	/** The signed-in ion username (empty until known, or if ion does not say). */
	FString GetAccountName() const { return AccountName; }
	/** Who and what the panel is connected to (the status pill's tooltip). */
	FString DescribeConnection() const;
	bool IsSingleUser() const { return bSingleUser; }

	/** Saves the server choice (ProjectSaved/CesiumIon/settings.json) and switches to it. */
	void SaveSettings(bool bInSelfHosted, const FString& InServerUrl, const FString& InApiUrl, bool bInSingleUser);

	/** For Cesium for Unreal: server URL and API URL, both ending in '/'. */
	FString GetServerUrl() const { return ServerUrl; }
	FString GetApiUrl() const { return ApiUrl; }
	/** Empty on a single-user server. */
	FString GetToken() const { return Token; }

	const TArray<FCesiumIonVrtakAsset>& GetAssets() const { return Assets; }
	void ListAssets(FCesiumIonVrtakAssetsResult OnDone);

	void SignIn(FCesiumIonVrtakSignInResult OnDone);
	void CancelSignIn();
	void SignOut();
	/** Looks up the signed-in username once per server and token (GET v1/me, scope profile:read). */
	void FetchAccount(FSimpleDelegate OnDone);

	virtual void BeginDestroy() override;

private:
	FString SettingsPath() const;
	FString TokenPath() const;
	void LoadToken();
	void SaveToken(const FString& NewToken);
	void StopCallbackServer();
	void FinishSignIn(bool bOk, const FString& Message);
	void ExchangeCode(const FString& Code);
	/** Handles the browser's return to the loopback address (after the route handler returns). */
	void HandleCallback(const TMap<FString, FString>& Query);

	bool bSelfHosted = false;
	bool bSingleUser = false;
	FString ServerUrl = TEXT("https://ion.cesium.com/");
	FString ApiUrl = TEXT("https://api.cesium.com/");
	FString Token;
	TArray<FCesiumIonVrtakAsset> Assets;
	FString AccountName;
	/** The server and token AccountName belongs to. */
	FString AccountFor;
	bool bLastListFailed = false;

	bool bSigningIn = false;
	FString Verifier;
	FString State;
	FCesiumIonVrtakSignInResult SignInDone;
	TSharedPtr<class IHttpRouter> Router;
	FHttpRouteHandle RouteHandle;
};
