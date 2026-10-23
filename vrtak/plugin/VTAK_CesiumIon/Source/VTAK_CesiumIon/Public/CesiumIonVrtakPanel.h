#pragma once

#include "CoreMinimal.h"
#include "Blueprint/UserWidget.h"
#include "UObject/WeakObjectPtr.h"
#include "CesiumIonVrtakPanel.generated.h"

class ACesiumIonVrtakActor;
class UBorder;
class UButton;
class UCheckBox;
class UEditableTextBox;
class UTextBlock;
class UVerticalBox;
struct FCesiumIonVrtakAsset;

/** The Add to map / Remove button of one asset row. */
UCLASS()
class UCesiumIonVrtakRowAction : public UObject
{
	GENERATED_BODY()

public:
	int64 AssetId = 0;
	FString Name;
	TWeakObjectPtr<class UCesiumIonVrtakPanel> Panel;

	UFUNCTION()
	void Click();
};

/**
 * The Cesium ion panel for VR-TAK, built in C++ (no Blueprint assets), in the
 * ATAK pane's design: Cesium black ground, Cesium blue actions, Cesium green
 * for what is on the map.
 *
 * Header: the Cesium ion wordmark, the connection status (hover: who is signed
 * in, or which server) and a settings button. Settings (closed by default):
 * account, sign in/out, and the server (Cesium ion Cloud or Self-Hosted).
 * Then a sign-in card while not signed in, what is in VR-TAK's world, featured
 * Cesium content (Cloud), and your assets with Add to map.
 */
UCLASS()
class UCesiumIonVrtakPanel : public UUserWidget
{
	GENERATED_BODY()

public:
	/** Name of Cesium content every ion Cloud account can add (Google Photorealistic 3D Tiles, ...), or empty. */
	static FString FeaturedName(int64 AssetId);
	/** ion's type of featured content (TERRAIN for Cesium World Terrain, ...); 3DTILES for anything else. */
	static FString FeaturedType(int64 AssetId);
	void SetOwnerActor(ACesiumIonVrtakActor* InOwner);
	/** Re-reads the session and the loaded assets. */
	void Refresh();
	void ShowMessage(const FString& Message);
	void ToggleAsset(int64 AssetId, const FString& Name);
	void ShowSettings(bool bShow);

protected:
	virtual TSharedRef<SWidget> RebuildWidget() override;

private:
	UFUNCTION()
	void HandleSignIn();
	UFUNCTION()
	void HandleRefresh();
	UFUNCTION()
	void HandleSettings();
	UFUNCTION()
	void HandleDone();
	UFUNCTION()
	void HandleCloudMode();
	UFUNCTION()
	void HandleSelfHostedMode();
	UFUNCTION()
	void HandleSave();

	UTextBlock* MakeText(const FString& Text, int32 Size, const FLinearColor& Color, bool bBold = false);
	UButton* MakeButton(const FString& Label, const FLinearColor& Fill, const FLinearColor& TextColor, UTextBlock** OutLabel = nullptr);
	UEditableTextBox* MakeField(const FString& Hint);
	UWidget* MakeAssetRow(const FCesiumIonVrtakAsset& Asset, bool bLoaded, bool bOnMapList, const FString& DetailOverride = FString());
	UWidget* BuildSettings();
	UWidget* BuildSignInCard();
	/** Loads the saved server into the form. */
	void LoadForm();
	void UpdateModeButtons();

	TWeakObjectPtr<ACesiumIonVrtakActor> OwnerActor;
	bool bFormSelfHosted = false;

	UPROPERTY()
	UBorder* StatusPill = nullptr;
	UPROPERTY()
	UTextBlock* StatusText = nullptr;
	UPROPERTY()
	UButton* SettingsButton = nullptr;
	UPROPERTY()
	UBorder* SettingsCard = nullptr;
	UPROPERTY()
	UTextBlock* ServerText = nullptr;
	UPROPERTY()
	UTextBlock* AuthText = nullptr;
	UPROPERTY()
	UButton* SignInButton = nullptr;
	UPROPERTY()
	UTextBlock* SignInLabel = nullptr;
	UPROPERTY()
	UButton* CloudModeButton = nullptr;
	UPROPERTY()
	UTextBlock* CloudModeLabel = nullptr;
	UPROPERTY()
	UButton* SelfHostedModeButton = nullptr;
	UPROPERTY()
	UTextBlock* SelfHostedModeLabel = nullptr;
	UPROPERTY()
	UVerticalBox* SelfHostedFields = nullptr;
	UPROPERTY()
	UEditableTextBox* ServerUrlBox = nullptr;
	UPROPERTY()
	UEditableTextBox* ApiUrlBox = nullptr;
	UPROPERTY()
	UCheckBox* SingleUserCheck = nullptr;
	UPROPERTY()
	UBorder* SignInCard = nullptr;
	UPROPERTY()
	UTextBlock* SignInCardText = nullptr;
	UPROPERTY()
	UTextBlock* SignInCtaLabel = nullptr;
	UPROPERTY()
	UVerticalBox* LoadedList = nullptr;
	UPROPERTY()
	UVerticalBox* FeaturedBox = nullptr;
	UPROPERTY()
	UVerticalBox* FeaturedList = nullptr;
	UPROPERTY()
	UVerticalBox* AssetList = nullptr;
	UPROPERTY()
	UTextBlock* AssetsTitle = nullptr;
	UPROPERTY()
	UTextBlock* MessageText = nullptr;
	UPROPERTY()
	TArray<UCesiumIonVrtakRowAction*> RowActions;
};
