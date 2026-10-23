#include "CesiumIonVrtakSession.h"

#include "VTAK_CesiumIon.h"

#include "Async/Async.h"
#include "Dom/JsonObject.h"
#include "GenericPlatform/GenericPlatformHttp.h"
#include "HttpModule.h"
#include "HttpPath.h"
#include "HttpServerModule.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformProcess.h"
#include "HttpServerRequest.h"
#include "HttpServerResponse.h"
#include "IHttpRouter.h"
#include "Interfaces/IHttpRequest.h"
#include "Interfaces/IHttpResponse.h"
#include "Misc/Base64.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/Guid.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Misc/SecureHash.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"

#if PLATFORM_WINDOWS
#include "Windows/AllowWindowsPlatformTypes.h"
#include <bcrypt.h>
#include <wincrypt.h>
#include "Windows/HideWindowsPlatformTypes.h"
#pragma comment(lib, "Bcrypt.lib")
#endif

namespace
{
	// The same public OAuth client and loopback redirect as the ATAK plugin.
	const TCHAR* const ClientId = TEXT("2438");
	const int32 CallbackPort = 38736;
	const TCHAR* const CallbackPath = TEXT("/cesium-tak/oauth2/callback");
	// Read-only: list and stream assets, and the username shown as who is signed in.
	const TCHAR* const Scopes = TEXT("assets:list assets:read profile:read");

	FString RedirectUri()
	{
		return FString::Printf(TEXT("http://127.0.0.1:%d%s"), CallbackPort, CallbackPath);
	}

	FString WithSlash(FString Url)
	{
		Url.TrimStartAndEndInline();
		if (!Url.IsEmpty() && !Url.EndsWith(TEXT("/")))
		{
			Url += TEXT("/");
		}
		return Url;
	}

	FString Base64Url(const TArray<uint8>& Bytes)
	{
		FString Text = FBase64::Encode(Bytes);
		Text.ReplaceInline(TEXT("+"), TEXT("-"));
		Text.ReplaceInline(TEXT("/"), TEXT("_"));
		Text.ReplaceInline(TEXT("="), TEXT(""));
		return Text;
	}

	/** Cryptographically random bytes (the PKCE verifier and state). */
	TArray<uint8> RandomBytes(int32 Count)
	{
		TArray<uint8> Bytes;
		Bytes.SetNumUninitialized(Count);
#if PLATFORM_WINDOWS
		if (BCryptGenRandom(nullptr, Bytes.GetData(), Count, BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0)
		{
			return Bytes;
		}
#endif
		// Fallback: GUIDs come from the OS's random generator.
		Bytes.Reset();
		while (Bytes.Num() < Count)
		{
			const FGuid Guid = FGuid::NewGuid();
			Bytes.Append(reinterpret_cast<const uint8*>(&Guid), sizeof(FGuid));
		}
		Bytes.SetNum(Count);
		return Bytes;
	}

	/** SHA-256 for the PKCE S256 challenge (CNG calls available since Windows Vista). */
	TArray<uint8> Sha256(const FString& Text)
	{
		TArray<uint8> Digest;
		const FTCHARToUTF8 Utf8(*Text);
#if PLATFORM_WINDOWS
		BCRYPT_ALG_HANDLE Algorithm = nullptr;
		BCRYPT_HASH_HANDLE Hash = nullptr;
		Digest.SetNumZeroed(32);
		const bool bOk = BCryptOpenAlgorithmProvider(&Algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0
			&& BCryptCreateHash(Algorithm, &Hash, nullptr, 0, nullptr, 0, 0) == 0
			&& BCryptHashData(Hash, (PUCHAR)Utf8.Get(), (ULONG)Utf8.Length(), 0) == 0
			&& BCryptFinishHash(Hash, Digest.GetData(), 32, 0) == 0;
		if (Hash)
		{
			BCryptDestroyHash(Hash);
		}
		if (Algorithm)
		{
			BCryptCloseAlgorithmProvider(Algorithm, 0);
		}
		if (bOk)
		{
			return Digest;
		}
#endif
		Digest.Reset();
		return Digest;
	}

	FString FormEncode(const TArray<TPair<FString, FString>>& Pairs)
	{
		FString Out;
		for (const TPair<FString, FString>& Pair : Pairs)
		{
			if (!Out.IsEmpty())
			{
				Out += TEXT("&");
			}
			Out += FGenericPlatformHttp::UrlEncode(Pair.Key) + TEXT("=") + FGenericPlatformHttp::UrlEncode(Pair.Value);
		}
		return Out;
	}

	const TCHAR* SignedInPage = TEXT(
		"<!doctype html><html><head><meta charset=\"utf-8\"><title>Signed in</title>"
		"<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
		"background:#0e1422;color:#e9eef5;font:16px/1.5 sans-serif;text-align:center}"
		"p{color:#8e9ab0}</style></head><body><div><h1>Signed in to Cesium ion</h1>"
		"<p>You can close this tab and return to VR-TAK.</p></div></body></html>");
}

void UCesiumIonVrtakSession::Load()
{
	FString Server;
	FString Api;
	if (FParse::Value(FCommandLine::Get(), TEXT("CesiumIonServer="), Server))
	{
		// Tests: a single-user Self-Hosted server, no token.
		bSelfHosted = true;
		bSingleUser = true;
		ServerUrl = WithSlash(Server);
		ApiUrl = FParse::Value(FCommandLine::Get(), TEXT("CesiumIonApi="), Api) ? WithSlash(Api) : ServerUrl;
	}
	else
	{
		FString Json;
		if (FFileHelper::LoadFileToString(Json, *SettingsPath()))
		{
			TSharedPtr<FJsonObject> Root;
			if (FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Root) && Root.IsValid())
			{
				bSelfHosted = Root->GetStringField(TEXT("mode")) == TEXT("selfhosted");
				bSingleUser = Root->HasField(TEXT("singleUser")) && Root->GetBoolField(TEXT("singleUser"));
				if (bSelfHosted)
				{
					ServerUrl = WithSlash(Root->GetStringField(TEXT("serverUrl")));
					ApiUrl = WithSlash(Root->HasField(TEXT("apiUrl")) ? Root->GetStringField(TEXT("apiUrl")) : ServerUrl);
				}
			}
		}
	}
	LoadToken();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion: %s (%s)."), *Describe(),
		NeedsToken() ? (HasToken() ? TEXT("signed in") : TEXT("not signed in")) : TEXT("no sign-in needed"));
}

FString UCesiumIonVrtakSession::Describe() const
{
	if (!bSelfHosted)
	{
		return TEXT("Cesium ion Cloud");
	}
	return FString::Printf(TEXT("Self-Hosted %s%s"), *ServerUrl, bSingleUser ? TEXT(" (single-user)") : TEXT(""));
}

FString UCesiumIonVrtakSession::DescribeConnection() const
{
	const FString Where = Describe();
	FString Text;
	if (!NeedsToken())
	{
		Text = bLastListFailed ? FString(TEXT("Cannot reach ")) + Where + TEXT(".")
			: FString(TEXT("Connected to ")) + Where + TEXT(". No sign-in needed.");
	}
	else if (!HasToken())
	{
		Text = FString(bSigningIn ? TEXT("Signing in to ") : TEXT("Not signed in to ")) + Where + TEXT(".");
	}
	else
	{
		Text = TEXT("Signed in to ") + Where + (AccountName.IsEmpty() ? FString() : TEXT(" as ") + AccountName) + TEXT(".");
	}
	return Text;
}

void UCesiumIonVrtakSession::SaveSettings(bool bInSelfHosted, const FString& InServerUrl, const FString& InApiUrl, bool bInSingleUser)
{
	bSelfHosted = bInSelfHosted;
	bSingleUser = bInSelfHosted && bInSingleUser;
	if (bSelfHosted)
	{
		ServerUrl = WithSlash(InServerUrl);
		ApiUrl = WithSlash(InApiUrl.TrimStartAndEnd().IsEmpty() ? InServerUrl : InApiUrl);
	}
	else
	{
		ServerUrl = TEXT("https://ion.cesium.com/");
		ApiUrl = TEXT("https://api.cesium.com/");
	}
	TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
	Root->SetStringField(TEXT("mode"), bSelfHosted ? TEXT("selfhosted") : TEXT("cloud"));
	if (bSelfHosted)
	{
		Root->SetStringField(TEXT("serverUrl"), ServerUrl);
		Root->SetStringField(TEXT("apiUrl"), ApiUrl);
		Root->SetBoolField(TEXT("singleUser"), bSingleUser);
	}
	FString Json;
	FJsonSerializer::Serialize(Root, TJsonWriterFactory<>::Create(&Json));
	FFileHelper::SaveStringToFile(Json, *SettingsPath());
	Assets.Reset();
	AccountName.Reset();
	AccountFor.Reset();
	bLastListFailed = false;
	LoadToken();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion server set to %s."), *Describe());
}

void UCesiumIonVrtakSession::FetchAccount(FSimpleDelegate OnDone)
{
	const FString Key = TokenPath() + TEXT("|") + Describe();
	if (!NeedsToken() || !HasToken() || Key == AccountFor)
	{
		OnDone.ExecuteIfBound();
		return;
	}
	AccountFor = Key;
	TSharedRef<IHttpRequest, ESPMode::ThreadSafe> Request = FHttpModule::Get().CreateRequest();
	Request->SetURL(ApiUrl + TEXT("v1/me"));
	Request->SetVerb(TEXT("GET"));
	Request->SetHeader(TEXT("Authorization"), TEXT("Bearer ") + Token);
	TWeakObjectPtr<UCesiumIonVrtakSession> Self(this);
	Request->OnProcessRequestComplete().BindLambda(
		[Self, OnDone](FHttpRequestPtr, FHttpResponsePtr Response, bool bConnected)
		{
			if (!Self.IsValid())
			{
				return;
			}
			TSharedPtr<FJsonObject> Root;
			FString Name;
			// Access saved before sign-in asked for profile:read gets 403: no name, still signed in.
			if (bConnected && Response.IsValid() && Response->GetResponseCode() == 200
				&& FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Response->GetContentAsString()), Root)
				&& Root.IsValid() && Root->TryGetStringField(TEXT("username"), Name))
			{
				Self->AccountName = Name;
				UE_LOG(LogCesiumIonVrtak, Log, TEXT("Signed in as %s."), *Name);
			}
			OnDone.ExecuteIfBound();
		});
	Request->ProcessRequest();
}

FString UCesiumIonVrtakSession::SettingsPath() const
{
	return FPaths::ProjectSavedDir() / TEXT("CesiumIon") / TEXT("settings.json");
}

FString UCesiumIonVrtakSession::TokenPath() const
{
	// One token per server, so switching servers never sends one's token to the other.
	const FString Slot = bSelfHosted ? FMD5::HashAnsiString(*ServerUrl) : TEXT("cloud");
	return FPaths::ProjectSavedDir() / TEXT("CesiumIon") / (TEXT("token-") + Slot + TEXT(".bin"));
}

void UCesiumIonVrtakSession::LoadToken()
{
	Token.Reset();
	TArray<uint8> Sealed;
	if (!NeedsToken() || !FFileHelper::LoadFileToArray(Sealed, *TokenPath(), FILEREAD_Silent))
	{
		return;
	}
#if PLATFORM_WINDOWS
	DATA_BLOB In{(DWORD)Sealed.Num(), Sealed.GetData()};
	DATA_BLOB Out{0, nullptr};
	if (CryptUnprotectData(&In, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &Out))
	{
		const FUTF8ToTCHAR Text((const ANSICHAR*)Out.pbData, (int32)Out.cbData);
		Token = FString(Text.Length(), Text.Get());
		LocalFree(Out.pbData);
	}
#endif
}

void UCesiumIonVrtakSession::SaveToken(const FString& NewToken)
{
	Token = NewToken;
#if PLATFORM_WINDOWS
	const FTCHARToUTF8 Utf8(*NewToken);
	DATA_BLOB In{(DWORD)Utf8.Length(), (BYTE*)Utf8.Get()};
	DATA_BLOB Out{0, nullptr};
	if (CryptProtectData(&In, TEXT("Cesium ion access"), nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &Out))
	{
		TArray<uint8> Sealed(Out.pbData, Out.cbData);
		LocalFree(Out.pbData);
		FFileHelper::SaveArrayToFile(Sealed, *TokenPath());
	}
#endif
}

void UCesiumIonVrtakSession::SignOut()
{
	Token.Reset();
	AccountName.Reset();
	AccountFor.Reset();
	IFileManager::Get().Delete(*TokenPath(), false, true, true);
	Assets.Reset();
}

void UCesiumIonVrtakSession::ListAssets(FCesiumIonVrtakAssetsResult OnDone)
{
	if (!IsReady())
	{
		OnDone.ExecuteIfBound(false, TEXT("Sign in to see your assets."));
		return;
	}
	TSharedRef<IHttpRequest, ESPMode::ThreadSafe> Request = FHttpModule::Get().CreateRequest();
	Request->SetURL(ApiUrl + TEXT("v1/assets?limit=100&sortBy=NAME&sortOrder=ASC"));
	Request->SetVerb(TEXT("GET"));
	if (HasToken())
	{
		Request->SetHeader(TEXT("Authorization"), TEXT("Bearer ") + Token);
	}
	TWeakObjectPtr<UCesiumIonVrtakSession> Self(this);
	Request->OnProcessRequestComplete().BindLambda(
		[Self, OnDone](FHttpRequestPtr, FHttpResponsePtr Response, bool bConnected)
		{
			if (!Self.IsValid())
			{
				return;
			}
			Self->bLastListFailed = !bConnected || !Response.IsValid() || Response->GetResponseCode() != 200;
			if (!bConnected || !Response.IsValid())
			{
				OnDone.ExecuteIfBound(false, TEXT("Could not reach the Cesium ion server."));
				return;
			}
			if (Response->GetResponseCode() == 401 || Response->GetResponseCode() == 403)
			{
				OnDone.ExecuteIfBound(false, TEXT("Cesium ion refused the saved access. Sign in again."));
				return;
			}
			TSharedPtr<FJsonObject> Root;
			if (Response->GetResponseCode() != 200
				|| !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Response->GetContentAsString()), Root)
				|| !Root.IsValid())
			{
				OnDone.ExecuteIfBound(false, FString::Printf(TEXT("Listing assets failed (HTTP %d)."), Response->GetResponseCode()));
				return;
			}
			Self->Assets.Reset();
			for (const TSharedPtr<FJsonValue>& Item : Root->GetArrayField(TEXT("items")))
			{
				const TSharedPtr<FJsonObject> Object = Item->AsObject();
				if (!Object.IsValid())
				{
					continue;
				}
				FCesiumIonVrtakAsset Asset;
				Asset.Id = (int64)Object->GetNumberField(TEXT("id"));
				Asset.Name = Object->GetStringField(TEXT("name"));
				Asset.Type = Object->GetStringField(TEXT("type"));
				Object->TryGetStringField(TEXT("status"), Asset.Status);
				double Bytes = 0;
				if (Object->TryGetNumberField(TEXT("bytes"), Bytes))
				{
					Asset.Bytes = (int64)Bytes;
				}
				Self->Assets.Add(Asset);
			}
			OnDone.ExecuteIfBound(true, FString::Printf(TEXT("Found %d assets."), Self->Assets.Num()));
		});
	Request->ProcessRequest();
}

void UCesiumIonVrtakSession::SignIn(FCesiumIonVrtakSignInResult OnDone)
{
	if (bSigningIn)
	{
		OnDone.ExecuteIfBound(false, TEXT("Sign-in is already open in the browser."));
		return;
	}
	Verifier = Base64Url(RandomBytes(48));
	State = Base64Url(RandomBytes(18));
	const TArray<uint8> Challenge = Sha256(Verifier);
	if (Challenge.Num() != 32)
	{
		OnDone.ExecuteIfBound(false, TEXT("Sign-in is not available on this platform."));
		return;
	}

	Router = FHttpServerModule::Get().GetHttpRouter(CallbackPort, /*bFailOnBindFailure*/ true);
	if (!Router.IsValid())
	{
		OnDone.ExecuteIfBound(false, TEXT("Another sign-in is still waiting; finish or close it, then try again."));
		return;
	}
	TWeakObjectPtr<UCesiumIonVrtakSession> Self(this);
	RouteHandle = Router->BindRoute(FHttpPath(CallbackPath), EHttpServerRequestVerbs::VERB_GET,
		// UE 5.3: FHttpRequestHandler is a TFunction.
		FHttpRequestHandler([Self](const FHttpServerRequest& Request, const FHttpResultCallback& OnComplete)
		{
			OnComplete(FHttpServerResponse::Create(SignedInPage, TEXT("text/html")));
			// The reply stops the callback server, which must not happen inside its own handler.
			const TMap<FString, FString> Query = Request.QueryParams;
			AsyncTask(ENamedThreads::GameThread, [Self, Query]()
			{
				if (Self.IsValid())
				{
					Self->HandleCallback(Query);
				}
			});
			return true;
		}));
	FHttpServerModule::Get().StartAllListeners();

	SignInDone = OnDone;
	bSigningIn = true;
	const FString Authorize = ServerUrl + TEXT("oauth?") + FormEncode({
		{TEXT("response_type"), TEXT("code")},
		{TEXT("client_id"), ClientId},
		{TEXT("redirect_uri"), RedirectUri()},
		{TEXT("scope"), Scopes},
		{TEXT("state"), State},
		{TEXT("code_challenge"), Base64Url(Challenge)},
		{TEXT("code_challenge_method"), TEXT("S256")},
	});
	FString LaunchError;
	FPlatformProcess::LaunchURL(*Authorize, nullptr, &LaunchError);
	if (!LaunchError.IsEmpty())
	{
		FinishSignIn(false, TEXT("Could not open the browser: ") + LaunchError);
	}
}

void UCesiumIonVrtakSession::HandleCallback(const TMap<FString, FString>& Query)
{
	if (!bSigningIn)
	{
		return;
	}
	const FString* Code = Query.Find(TEXT("code"));
	const FString* ReplyState = Query.Find(TEXT("state"));
	const FString* Error = Query.Find(TEXT("error"));
	if (Error)
	{
		FinishSignIn(false, TEXT("Cesium ion did not sign you in (") + *Error + TEXT(")."));
	}
	else if (!Code || !ReplyState || *ReplyState != State)
	{
		FinishSignIn(false, TEXT("The sign-in reply did not match this request; try again."));
	}
	else
	{
		// The loopback server is done; the code is exchanged over HTTPS.
		StopCallbackServer();
		ExchangeCode(*Code);
	}
}

void UCesiumIonVrtakSession::ExchangeCode(const FString& Code)
{
	TSharedRef<FJsonObject> Body = MakeShared<FJsonObject>();
	Body->SetStringField(TEXT("grant_type"), TEXT("authorization_code"));
	Body->SetStringField(TEXT("client_id"), ClientId);
	Body->SetStringField(TEXT("code"), Code);
	Body->SetStringField(TEXT("redirect_uri"), RedirectUri());
	Body->SetStringField(TEXT("code_verifier"), Verifier);
	FString Json;
	FJsonSerializer::Serialize(Body, TJsonWriterFactory<>::Create(&Json));

	TSharedRef<IHttpRequest, ESPMode::ThreadSafe> Request = FHttpModule::Get().CreateRequest();
	Request->SetURL(ApiUrl + TEXT("oauth/token"));
	Request->SetVerb(TEXT("POST"));
	Request->SetHeader(TEXT("Content-Type"), TEXT("application/json"));
	Request->SetContentAsString(Json);
	TWeakObjectPtr<UCesiumIonVrtakSession> Self(this);
	Request->OnProcessRequestComplete().BindLambda(
		[Self](FHttpRequestPtr, FHttpResponsePtr Response, bool bConnected)
		{
			if (!Self.IsValid())
			{
				return;
			}
			TSharedPtr<FJsonObject> Root;
			FString AccessToken;
			if (bConnected && Response.IsValid() && Response->GetResponseCode() == 200
				&& FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Response->GetContentAsString()), Root)
				&& Root.IsValid() && Root->TryGetStringField(TEXT("access_token"), AccessToken) && !AccessToken.IsEmpty())
			{
				Self->SaveToken(AccessToken);
				Self->FinishSignIn(true, TEXT("Signed in to ") + Self->Describe() + TEXT("."));
			}
			else
			{
				Self->FinishSignIn(false, FString::Printf(TEXT("Cesium ion did not issue access (HTTP %d)."),
					Response.IsValid() ? Response->GetResponseCode() : 0));
			}
		});
	Request->ProcessRequest();
}

void UCesiumIonVrtakSession::CancelSignIn()
{
	if (bSigningIn)
	{
		FinishSignIn(false, TEXT("Sign-in cancelled."));
	}
}

void UCesiumIonVrtakSession::FinishSignIn(bool bOk, const FString& Message)
{
	bSigningIn = false;
	Verifier.Reset();
	State.Reset();
	StopCallbackServer();
	FCesiumIonVrtakSignInResult Done = SignInDone;
	SignInDone.Unbind();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("%s"), *Message);
	Done.ExecuteIfBound(bOk, Message);
}

void UCesiumIonVrtakSession::StopCallbackServer()
{
	if (Router.IsValid() && RouteHandle.IsValid())
	{
		Router->UnbindRoute(RouteHandle);
	}
	RouteHandle.Reset();
	Router.Reset();
}

void UCesiumIonVrtakSession::BeginDestroy()
{
	StopCallbackServer();
	Super::BeginDestroy();
}
