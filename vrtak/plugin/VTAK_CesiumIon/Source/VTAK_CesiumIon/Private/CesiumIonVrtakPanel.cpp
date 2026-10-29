#include "CesiumIonVrtakPanel.h"

#include "CesiumIonVrtakActor.h"
#include "CesiumIonVrtakSession.h"

#include "Blueprint/WidgetTree.h"
#include "Components/Border.h"
#include "Components/Button.h"
#include "Components/CheckBox.h"
#include "Components/EditableTextBox.h"
#include "Components/HorizontalBox.h"
#include "Components/HorizontalBoxSlot.h"
#include "Components/Image.h"
#include "Components/ScrollBox.h"
#include "Components/Spacer.h"
#include "Components/TextBlock.h"
#include "Components/VerticalBox.h"
#include "Components/VerticalBoxSlot.h"
#include "Engine/Texture2D.h"
#include "ImageUtils.h"
#include "Interfaces/IPluginManager.h"
#include "Misc/Paths.h"

namespace
{
	// Cesium's brand guide (cesium.com/press), as in the ATAK pane.
	const FLinearColor Ground = FLinearColor::FromSRGBColor(FColor(14, 20, 34));
	const FLinearColor Card = FLinearColor::FromSRGBColor(FColor(23, 32, 51));
	const FLinearColor Text = FLinearColor::FromSRGBColor(FColor(233, 238, 245));
	const FLinearColor Muted = FLinearColor::FromSRGBColor(FColor(142, 154, 176));
	const FLinearColor Blue = FLinearColor::FromSRGBColor(FColor(109, 171, 228));
	const FLinearColor Green = FLinearColor::FromSRGBColor(FColor(150, 194, 106));
	const FLinearColor Amber = FLinearColor::FromSRGBColor(FColor(227, 176, 75));
	const FLinearColor Danger = FLinearColor::FromSRGBColor(FColor(232, 120, 100));

	struct FFeatured
	{
		int64 Id;
		const TCHAR* Name;
		const TCHAR* Detail;
		const TCHAR* Type;
	};
	// Cesium content every ion Cloud account can add (as in the ATAK pane).
	const FFeatured Featured[] = {
		{2275207, TEXT("Google Photorealistic 3D Tiles"), TEXT("Photorealistic 3D cities and terrain, worldwide"), TEXT("3DTILES")},
		{96188, TEXT("Cesium OSM Buildings"), TEXT("3D buildings from OpenStreetMap, worldwide"), TEXT("3DTILES")},
		{1, TEXT("Cesium World Terrain"), TEXT("High-resolution terrain, worldwide"), TEXT("TERRAIN")},
		{2, TEXT("Bing Maps Aerial"), TEXT("Aerial imagery on the Cesium terrain, worldwide"), TEXT("IMAGERY")},
	};

	FString DescribeType(const FString& Type)
	{
		if (Type == TEXT("3DTILES")) return TEXT("3D Tiles");
		if (Type == TEXT("IMAGERY")) return TEXT("Imagery");
		if (Type == TEXT("TERRAIN")) return TEXT("Terrain");
		if (Type == TEXT("GEOJSON")) return TEXT("GeoJSON");
		return Type;
	}

	UTexture2D* LoadResource(const TCHAR* FileName, TWeakObjectPtr<UTexture2D>& Cached)
	{
		if (!Cached.IsValid())
		{
			TSharedPtr<IPlugin> Plugin = IPluginManager::Get().FindPlugin(TEXT("VTAK_CesiumIon"));
			if (Plugin.IsValid())
			{
				Cached = FImageUtils::ImportFileAsTexture2D(Plugin->GetBaseDir() / TEXT("Resources") / FileName);
			}
		}
		return Cached.Get();
	}

	UTexture2D* LoadLogo()
	{
		static TWeakObjectPtr<UTexture2D> Cached;
		return LoadResource(TEXT("cesium_ion_logo.png"), Cached);
	}

	UTexture2D* LoadGear()
	{
		static TWeakObjectPtr<UTexture2D> Cached;
		return LoadResource(TEXT("gear.png"), Cached);
	}
}

void UCesiumIonVrtakRowAction::GoTo()
{
	if (Panel.IsValid())
	{
		Panel->GoToAsset(AssetId);
	}
}

void UCesiumIonVrtakRowAction::Click()
{
	if (Panel.IsValid())
	{
		Panel->ToggleAsset(AssetId, Name);
	}
}

void UCesiumIonVrtakPanel::SetOwnerActor(ACesiumIonVrtakActor* InOwner)
{
	OwnerActor = InOwner;
}

UTextBlock* UCesiumIonVrtakPanel::MakeText(const FString& Value, int32 Size, const FLinearColor& Color, bool bBold)
{
	UTextBlock* Block = WidgetTree->ConstructWidget<UTextBlock>(UTextBlock::StaticClass());
	Block->SetText(FText::FromString(Value));
	Block->SetColorAndOpacity(FSlateColor(Color));
	FSlateFontInfo Font = Block->GetFont();
	Font.Size = Size;
	Font.TypefaceFontName = bBold ? FName(TEXT("Bold")) : FName(TEXT("Regular"));
	Block->SetFont(Font);
	Block->SetAutoWrapText(true);
	return Block;
}

UButton* UCesiumIonVrtakPanel::MakeButton(const FString& Label, const FLinearColor& Fill, const FLinearColor& TextColor, UTextBlock** OutLabel)
{
	UButton* Button = WidgetTree->ConstructWidget<UButton>(UButton::StaticClass());
	Button->SetBackgroundColor(Fill);
	UTextBlock* Caption = MakeText(Label, 14, TextColor, true);
	Caption->SetAutoWrapText(false);
	Button->AddChild(Caption);
	if (OutLabel)
	{
		*OutLabel = Caption;
	}
	return Button;
}

UEditableTextBox* UCesiumIonVrtakPanel::MakeField(const FString& Hint)
{
	UEditableTextBox* Field = WidgetTree->ConstructWidget<UEditableTextBox>(UEditableTextBox::StaticClass());
	Field->SetHintText(FText::FromString(Hint));
	return Field;
}

TSharedRef<SWidget> UCesiumIonVrtakPanel::RebuildWidget()
{
	if (WidgetTree && !WidgetTree->RootWidget)
	{
		UBorder* Root = WidgetTree->ConstructWidget<UBorder>(UBorder::StaticClass());
		Root->SetBrushColor(Ground);
		Root->SetPadding(FMargin(20.f));
		WidgetTree->RootWidget = Root;

		UVerticalBox* Column = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		Root->AddChild(Column);

		// Header: the wordmark, the connection status (hover: who and what) and settings.
		UHorizontalBox* Header = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
		if (UTexture2D* Logo = LoadLogo())
		{
			UImage* Image = WidgetTree->ConstructWidget<UImage>(UImage::StaticClass());
			// The wordmark at its own proportions, 32 units high (a texture brush defaults to 32 x 32).
			FSlateBrush Brush;
			Brush.SetResourceObject(Logo);
			Brush.ImageSize = FVector2D(32.f * Logo->GetSizeX() / FMath::Max(1, Logo->GetSizeY()), 32.f);
			Image->SetBrush(Brush);
			Header->AddChildToHorizontalBox(Image)->SetVerticalAlignment(VAlign_Center);
		}
		else
		{
			Header->AddChildToHorizontalBox(MakeText(TEXT("Cesium ion"), 22, Text, true));
		}
		UHorizontalBoxSlot* Fill = Header->AddChildToHorizontalBox(WidgetTree->ConstructWidget<USpacer>(USpacer::StaticClass()));
		Fill->SetSize(FSlateChildSize(ESlateSizeRule::Fill));
		StatusPill = WidgetTree->ConstructWidget<UBorder>(UBorder::StaticClass());
		StatusPill->SetPadding(FMargin(12.f, 5.f));
		StatusText = MakeText(TEXT(""), 14, Muted, true);
		StatusText->SetAutoWrapText(false);
		StatusPill->AddChild(StatusText);
		Header->AddChildToHorizontalBox(StatusPill)->SetVerticalAlignment(VAlign_Center);
		SettingsButton = WidgetTree->ConstructWidget<UButton>(UButton::StaticClass());
		SettingsButton->SetBackgroundColor(Card);
		SettingsButton->SetToolTipText(FText::FromString(TEXT("Settings")));
		if (UTexture2D* Gear = LoadGear())
		{
			UImage* GearImage = WidgetTree->ConstructWidget<UImage>(UImage::StaticClass());
			FSlateBrush GearBrush;
			GearBrush.SetResourceObject(Gear);
			GearBrush.ImageSize = FVector2D(22.f, 22.f);
			GearImage->SetBrush(GearBrush);
			SettingsButton->AddChild(GearImage);
		}
		else
		{
			SettingsButton->AddChild(MakeText(TEXT("Settings"), 13, Text, true));
		}
		SettingsButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSettings);
		Header->AddChildToHorizontalBox(SettingsButton)->SetPadding(FMargin(8.f, 0.f, 0.f, 0.f));
		UButton* HideButton = MakeButton(TEXT("Hide"), Card, Text);
		HideButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleHide);
		Header->AddChildToHorizontalBox(HideButton)->SetPadding(FMargin(8.f, 0.f, 0.f, 0.f));
		Column->AddChildToVerticalBox(Header)->SetPadding(FMargin(0.f, 0.f, 0.f, 12.f));

		MessageText = MakeText(TEXT(""), 13, Amber);
		Column->AddChildToVerticalBox(MessageText)->SetPadding(FMargin(0.f, 0.f, 0.f, 8.f));
		Column->AddChildToVerticalBox(BuildPlacement())->SetPadding(FMargin(0.f, 0.f, 0.f, 8.f));
		Column->AddChildToVerticalBox(BuildSettings())->SetPadding(FMargin(0.f, 0.f, 0.f, 12.f));
		Column->AddChildToVerticalBox(BuildSignInCard())->SetPadding(FMargin(0.f, 0.f, 0.f, 8.f));

		// One scrolling column: what is in the world, featured content, your assets.
		UScrollBox* Scroll = WidgetTree->ConstructWidget<UScrollBox>(UScrollBox::StaticClass());
		UVerticalBox* Content = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		Scroll->AddChild(Content);
		UVerticalBoxSlot* ScrollSlot = Column->AddChildToVerticalBox(Scroll);
		ScrollSlot->SetSize(FSlateChildSize(ESlateSizeRule::Fill));

		Content->AddChildToVerticalBox(MakeText(TEXT("In VR-TAK's world"), 18, Text, true))->SetPadding(FMargin(0.f, 8.f, 0.f, 6.f));
		LoadedList = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		Content->AddChildToVerticalBox(LoadedList);

		FeaturedBox = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		FeaturedBox->AddChildToVerticalBox(MakeText(TEXT("From Cesium"), 18, Text, true))->SetPadding(FMargin(0.f, 20.f, 0.f, 6.f));
		FeaturedList = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		FeaturedBox->AddChildToVerticalBox(FeaturedList);
		Content->AddChildToVerticalBox(FeaturedBox);

		AssetsTitle = MakeText(TEXT("Your assets"), 18, Text, true);
		Content->AddChildToVerticalBox(AssetsTitle)->SetPadding(FMargin(0.f, 20.f, 0.f, 6.f));
		Content->AddChildToVerticalBox(BuildAssetControls());
		AssetList = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
		Content->AddChildToVerticalBox(AssetList);
		Content->AddChildToVerticalBox(BuildPager())->SetPadding(FMargin(0.f, 8.f, 0.f, 0.f));

	}
	return Super::RebuildWidget();
}

UWidget* UCesiumIonVrtakPanel::BuildSettings()
{
	SettingsCard = WidgetTree->ConstructWidget<UBorder>(UBorder::StaticClass());
	SettingsCard->SetBrushColor(Card);
	SettingsCard->SetPadding(FMargin(16.f, 12.f));
	SettingsCard->SetVisibility(ESlateVisibility::Collapsed);
	UVerticalBox* Box = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
	SettingsCard->AddChild(Box);

	UHorizontalBox* Title = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	UHorizontalBoxSlot* TitleSlot = Title->AddChildToHorizontalBox(MakeText(TEXT("Settings"), 16, Text, true));
	TitleSlot->SetSize(FSlateChildSize(ESlateSizeRule::Fill));
	TitleSlot->SetVerticalAlignment(VAlign_Center);
	UButton* Done = MakeButton(TEXT("Done"), Card, Blue);
	Done->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleDone);
	Title->AddChildToHorizontalBox(Done);
	Box->AddChildToVerticalBox(Title);

	// Account.
	ServerText = MakeText(TEXT(""), 15, Text, true);
	Box->AddChildToVerticalBox(ServerText)->SetPadding(FMargin(0.f, 6.f, 0.f, 0.f));
	AuthText = MakeText(TEXT(""), 13, Muted);
	Box->AddChildToVerticalBox(AuthText)->SetPadding(FMargin(0.f, 2.f, 0.f, 10.f));
	UHorizontalBox* Actions = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	SignInButton = MakeButton(TEXT("Sign in with Cesium ion"), Blue, Ground, &SignInLabel);
	SignInButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSignIn);
	Actions->AddChildToHorizontalBox(SignInButton);
	UButton* RefreshButton = MakeButton(TEXT("Refresh"), Card, Blue);
	RefreshButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleRefresh);
	Actions->AddChildToHorizontalBox(RefreshButton)->SetPadding(FMargin(8.f, 0.f, 0.f, 0.f));
	Box->AddChildToVerticalBox(Actions);

	// Server: Cesium ion Cloud or Self-Hosted.
	Box->AddChildToVerticalBox(MakeText(TEXT("Server"), 14, Text, true))->SetPadding(FMargin(0.f, 16.f, 0.f, 6.f));
	UHorizontalBox* Modes = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	CloudModeButton = MakeButton(TEXT("Cesium ion Cloud"), Card, Text, &CloudModeLabel);
	CloudModeButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleCloudMode);
	Modes->AddChildToHorizontalBox(CloudModeButton);
	SelfHostedModeButton = MakeButton(TEXT("Self-Hosted"), Card, Text, &SelfHostedModeLabel);
	SelfHostedModeButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSelfHostedMode);
	Modes->AddChildToHorizontalBox(SelfHostedModeButton)->SetPadding(FMargin(8.f, 0.f, 0.f, 0.f));
	Box->AddChildToVerticalBox(Modes);

	SelfHostedFields = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
	SelfHostedFields->AddChildToVerticalBox(MakeText(TEXT("Server URL"), 12, Muted))->SetPadding(FMargin(0.f, 10.f, 0.f, 2.f));
	ServerUrlBox = MakeField(TEXT("https://ion.example.org/"));
	SelfHostedFields->AddChildToVerticalBox(ServerUrlBox);
	SelfHostedFields->AddChildToVerticalBox(MakeText(TEXT("API URL (if different)"), 12, Muted))->SetPadding(FMargin(0.f, 8.f, 0.f, 2.f));
	ApiUrlBox = MakeField(TEXT("Same as the server URL"));
	SelfHostedFields->AddChildToVerticalBox(ApiUrlBox);
	UHorizontalBox* SingleUserRow = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	SingleUserCheck = WidgetTree->ConstructWidget<UCheckBox>(UCheckBox::StaticClass());
	SingleUserRow->AddChildToHorizontalBox(SingleUserCheck)->SetVerticalAlignment(VAlign_Center);
	SingleUserRow->AddChildToHorizontalBox(MakeText(TEXT("Single-user server (no sign-in)"), 13, Text))
		->SetPadding(FMargin(6.f, 0.f, 0.f, 0.f));
	SelfHostedFields->AddChildToVerticalBox(SingleUserRow)->SetPadding(FMargin(0.f, 8.f, 0.f, 0.f));
	Box->AddChildToVerticalBox(SelfHostedFields);

	UButton* Save = MakeButton(TEXT("Save"), Blue, Ground);
	Save->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSave);
	UHorizontalBox* SaveRow = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	SaveRow->AddChildToHorizontalBox(Save);
	Box->AddChildToVerticalBox(SaveRow)->SetPadding(FMargin(0.f, 12.f, 0.f, 0.f));
	return SettingsCard;
}

UWidget* UCesiumIonVrtakPanel::BuildSignInCard()
{
	SignInCard = WidgetTree->ConstructWidget<UBorder>(UBorder::StaticClass());
	SignInCard->SetBrushColor(Card);
	SignInCard->SetPadding(FMargin(16.f, 14.f));
	UVerticalBox* Box = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
	SignInCard->AddChild(Box);
	Box->AddChildToVerticalBox(MakeText(TEXT("Sign in to Cesium ion"), 16, Text, true));
	SignInCardText = MakeText(TEXT(""), 13, Muted);
	Box->AddChildToVerticalBox(SignInCardText)->SetPadding(FMargin(0.f, 4.f, 0.f, 10.f));
	UHorizontalBox* Actions = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	UButton* Cta = MakeButton(TEXT("Sign in with Cesium ion"), Blue, Ground, &SignInCtaLabel);
	Cta->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSignIn);
	Actions->AddChildToHorizontalBox(Cta);
	UButton* ServerSettings = MakeButton(TEXT("Server settings"), Card, Blue);
	ServerSettings->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSettings);
	Actions->AddChildToHorizontalBox(ServerSettings)->SetPadding(FMargin(8.f, 0.f, 0.f, 0.f));
	Box->AddChildToVerticalBox(Actions);
	return SignInCard;
}

UWidget* UCesiumIonVrtakPanel::MakeAssetRow(const FCesiumIonVrtakAsset& Asset, bool bLoaded, bool bOnMapList, const FString& DetailOverride)
{
	UHorizontalBox* Row = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	UVerticalBox* Labels = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
	Labels->AddChildToVerticalBox(MakeText(Asset.Name, 15, Text, true));
	const FString Detail = bOnMapList
		? DescribeType(Asset.Type) + (Asset.Type == TEXT("IMAGERY") ? TEXT(", over the Cesium terrain") : TEXT(", aligned with VR-TAK's globe"))
		: !DetailOverride.IsEmpty() ? DetailOverride + (bLoaded ? TEXT(", in the world") : TEXT(""))
		: FString::Printf(TEXT("%s, ID %lld%s"), *DescribeType(Asset.Type), Asset.Id, bLoaded ? TEXT(", in the world") : TEXT(""));
	Labels->AddChildToVerticalBox(MakeText(Detail, 12, bLoaded && !bOnMapList ? Green : Muted));
	UHorizontalBoxSlot* LabelSlot = Row->AddChildToHorizontalBox(Labels);
	LabelSlot->SetSize(FSlateChildSize(ESlateSizeRule::Fill));
	LabelSlot->SetVerticalAlignment(VAlign_Center);

	const bool bSupported = ACesiumIonVrtakActor::IsSupportedType(Asset.Type);
	UButton* Button = bLoaded
		? MakeButton(TEXT("Remove"), Card, Danger)
		: MakeButton(bSupported ? TEXT("Add to map") : TEXT("Not yet"), bSupported ? Blue : Card, bSupported ? Ground : Muted);
	Button->SetIsEnabled(bLoaded || bSupported);
	if (!bLoaded && !bSupported)
	{
		Button->SetToolTipText(FText::FromString(TEXT("VR-TAK shows Cesium ion 3D Tiles, terrain and imagery today.")));
	}
	else if (!bLoaded && Asset.Type == TEXT("IMAGERY"))
	{
		Button->SetToolTipText(FText::FromString(TEXT("Drapes over the Cesium terrain you add.")));
	}
	UCesiumIonVrtakRowAction* Action = NewObject<UCesiumIonVrtakRowAction>(this);
	Action->AssetId = Asset.Id;
	Action->Name = Asset.Name;
	Action->Panel = this;
	RowActions.Add(Action);
	Button->OnClicked.AddDynamic(Action, &UCesiumIonVrtakRowAction::Click);
	if (bOnMapList && Asset.Type == TEXT("3DTILES"))
	{
		// View it from outside (not at its own position).
		UButton* View = MakeButton(TEXT("View"), Card, Blue);
		View->OnClicked.AddDynamic(Action, &UCesiumIonVrtakRowAction::GoTo);
		Row->AddChildToHorizontalBox(View)->SetPadding(FMargin(0.f, 0.f, 6.f, 0.f));
	}
	Row->AddChildToHorizontalBox(Button)->SetVerticalAlignment(VAlign_Center);

	UBorder* Frame = WidgetTree->ConstructWidget<UBorder>(UBorder::StaticClass());
	Frame->SetBrushColor(bOnMapList ? Card : Ground);
	Frame->SetPadding(FMargin(bOnMapList ? 12.f : 0.f, 8.f));
	Frame->AddChild(Row);
	return Frame;
}

void UCesiumIonVrtakPanel::Refresh()
{
	if (!StatusText || !OwnerActor.IsValid())
	{
		return;
	}
	UCesiumIonVrtakSession* Session = OwnerActor->GetSession();
	const bool bReady = Session->IsReady();
	const FLinearColor StatusColor = bReady ? Green : Amber;
	StatusText->SetText(FText::FromString(FString(TEXT("\u2022  ")) + (bReady ? TEXT("Connected") : Session->IsSigningIn() ? TEXT("Signing in") : TEXT("Not signed in"))));
	StatusText->SetColorAndOpacity(FSlateColor(StatusColor));
	StatusPill->SetBrushColor(FLinearColor(StatusColor.R, StatusColor.G, StatusColor.B, 0.14f));
	// Hover: who is signed in, or which server.
	StatusPill->SetToolTipText(FText::FromString(Session->DescribeConnection()));

	ServerText->SetText(FText::FromString(Session->Describe()));
	const FString Name = Session->GetAccountName();
	AuthText->SetText(FText::FromString(!Session->NeedsToken() ? TEXT("No sign-in needed on this server.")
		: Session->HasToken() ? (Name.IsEmpty() ? FString(TEXT("Signed in.")) : TEXT("Signed in as ") + Name + TEXT("."))
			+ TEXT(" Your access is saved for this Windows user.")
		: Session->IsSigningIn() ? FString(TEXT("Finish signing in in the browser, then come back here."))
		: FString(TEXT("Sign in with your Cesium ion account to see your assets here."))));
	SignInButton->SetVisibility(Session->NeedsToken() ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);
	SignInLabel->SetText(FText::FromString(Session->IsSigningIn() ? TEXT("Cancel sign-in")
		: Session->HasToken() ? TEXT("Sign out") : TEXT("Sign in with Cesium ion")));
	SignInButton->SetBackgroundColor(Session->HasToken() || Session->IsSigningIn() ? Card : Blue);
	SignInLabel->SetColorAndOpacity(FSlateColor(Session->HasToken() || Session->IsSigningIn() ? Text : Ground));

	// First use: the sign-in card, in the main panel.
	// (Not while settings are open: they offer the same sign-in.)
	const bool bNeedsSignIn = Session->NeedsToken() && !Session->HasToken();
	const bool bSettingsOpen = SettingsCard->GetVisibility() == ESlateVisibility::Visible;
	SignInCard->SetVisibility(bNeedsSignIn && !bSettingsOpen ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);
	SignInCardText->SetText(FText::FromString(TEXT("Sign in to ") + Session->Describe()
		+ TEXT(" to see your assets here and add them to VR-TAK's world.")));
	SignInCtaLabel->SetText(FText::FromString(Session->IsSigningIn() ? TEXT("Cancel sign-in") : TEXT("Sign in with Cesium ion")));

	RowActions.Reset();
	LoadedList->ClearChildren();
	const TArray<TPair<int64, FString>> Loaded = OwnerActor->GetLoaded();
	if (Loaded.Num() == 0)
	{
		LoadedList->AddChildToVerticalBox(MakeText(TEXT("Nothing from Cesium ion yet. Add 3D Tiles, terrain or imagery below."), 13, Muted));
	}
	for (const TPair<int64, FString>& Entry : Loaded)
	{
		FCesiumIonVrtakAsset Asset;
		Asset.Id = Entry.Key;
		Asset.Name = Entry.Value;
		Asset.Type = OwnerActor->GetLoadedType(Entry.Key);
		LoadedList->AddChildToVerticalBox(MakeAssetRow(Asset, true, true))->SetPadding(FMargin(0.f, 0.f, 0.f, 6.f));
	}

	// Featured Cesium content (ion Cloud), unless it is already in the asset list.
	const TArray<FCesiumIonVrtakAsset>& Assets = Session->GetAssets();
	FeaturedList->ClearChildren();
	int32 FeaturedShown = 0;
	if (!Session->IsSelfHosted() && bReady)
	{
		for (const FFeatured& Each : Featured)
		{
			if (Assets.ContainsByPredicate([&Each](const FCesiumIonVrtakAsset& A) { return A.Id == Each.Id; }))
			{
				continue;
			}
			FCesiumIonVrtakAsset Asset;
			Asset.Id = Each.Id;
			Asset.Name = Each.Name;
			Asset.Type = Each.Type;
			FeaturedList->AddChildToVerticalBox(MakeAssetRow(Asset, OwnerActor->IsLoaded(Each.Id), false, Each.Detail));
			FeaturedShown++;
		}
	}
	FeaturedBox->SetVisibility(FeaturedShown > 0 ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);

	RebuildAssetList();
	if (PlacementRow)
	{
		PlacementRow->SetVisibility(OwnerActor->IsPanelFloating() ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);
	}
	if (FollowLabel)
	{
		FollowLabel->SetText(FText::FromString(OwnerActor->IsFollowingPanel() ? TEXT("Follow: on") : TEXT("Follow: off")));
	}
}

UWidget* UCesiumIonVrtakPanel::BuildAssetControls()
{
	UVerticalBox* Box = WidgetTree->ConstructWidget<UVerticalBox>(UVerticalBox::StaticClass());
	SearchBox = MakeField(TEXT("Search your assets (name or ID)"));
	SearchBox->OnTextChanged.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSearchChanged);
	Box->AddChildToVerticalBox(SearchBox)->SetPadding(FMargin(0.f, 0.f, 0.f, 8.f));
	UHorizontalBox* Chips = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	AllChip = MakeButton(TEXT("All"), Blue, Ground, &AllChipLabel);
	AllChip->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleFilterAll);
	Chips->AddChildToHorizontalBox(AllChip)->SetPadding(FMargin(0.f, 0.f, 6.f, 0.f));
	TilesChip = MakeButton(TEXT("3D Tiles"), Card, Text, &TilesChipLabel);
	TilesChip->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleFilterTiles);
	Chips->AddChildToHorizontalBox(TilesChip)->SetPadding(FMargin(0.f, 0.f, 6.f, 0.f));
	ImageryChip = MakeButton(TEXT("Imagery"), Card, Text, &ImageryChipLabel);
	ImageryChip->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleFilterImagery);
	Chips->AddChildToHorizontalBox(ImageryChip)->SetPadding(FMargin(0.f, 0.f, 6.f, 0.f));
	TerrainChip = MakeButton(TEXT("Terrain"), Card, Text, &TerrainChipLabel);
	TerrainChip->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleFilterTerrain);
	Chips->AddChildToHorizontalBox(TerrainChip);
	Box->AddChildToVerticalBox(Chips)->SetPadding(FMargin(0.f, 0.f, 0.f, 8.f));
	return Box;
}

UWidget* UCesiumIonVrtakPanel::BuildPager()
{
	UHorizontalBox* Row = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	PrevButton = MakeButton(TEXT("< Previous"), Card, Text);
	PrevButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandlePrevPage);
	Row->AddChildToHorizontalBox(PrevButton);
	PageLabel = MakeText(TEXT(""), 13, Muted);
	UHorizontalBoxSlot* LabelSlot = Row->AddChildToHorizontalBox(PageLabel);
	LabelSlot->SetSize(FSlateChildSize(ESlateSizeRule::Fill));
	LabelSlot->SetHorizontalAlignment(HAlign_Center);
	LabelSlot->SetVerticalAlignment(VAlign_Center);
	NextButton = MakeButton(TEXT("Next >"), Card, Text);
	NextButton->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleNextPage);
	Row->AddChildToHorizontalBox(NextButton);
	return Row;
}

UWidget* UCesiumIonVrtakPanel::BuildPlacement()
{
	// Only shown while the panel floats in the world (VR).
	PlacementRow = WidgetTree->ConstructWidget<UHorizontalBox>(UHorizontalBox::StaticClass());
	PlacementRow->SetVisibility(ESlateVisibility::Collapsed);
	auto Add = [this](UButton* Button, bool bLast)
	{
		PlacementRow->AddChildToHorizontalBox(Button)->SetPadding(FMargin(0.f, 0.f, bLast ? 0.f : 6.f, 0.f));
	};
	UButton* Left = MakeButton(TEXT("Left"), Card, Text);
	Left->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleDockLeft);
	Add(Left, false);
	UButton* Front = MakeButton(TEXT("Front"), Card, Text);
	Front->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleDockFront);
	Add(Front, false);
	UButton* Right = MakeButton(TEXT("Right"), Card, Text);
	Right->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleDockRight);
	Add(Right, false);
	UButton* Follow = MakeButton(TEXT("Follow: off"), Card, Text, &FollowLabel);
	Follow->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleFollow);
	Add(Follow, false);
	UButton* Smaller = MakeButton(TEXT("-"), Card, Text);
	Smaller->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleSmaller);
	Add(Smaller, false);
	UButton* Bigger = MakeButton(TEXT("+"), Card, Text);
	Bigger->OnClicked.AddDynamic(this, &UCesiumIonVrtakPanel::HandleBigger);
	Add(Bigger, true);
	return PlacementRow;
}

void UCesiumIonVrtakPanel::SetChipStyle(UButton* Chip, UTextBlock* Label, bool bActive)
{
	if (Chip && Label)
	{
		Chip->SetBackgroundColor(bActive ? Blue : Card);
		Label->SetColorAndOpacity(FSlateColor(bActive ? Ground : Text));
	}
}

void UCesiumIonVrtakPanel::SetTypeFilter(const FString& Type)
{
	TypeFilter = Type;
	PageIndex = 0;
	RebuildAssetList();
}

void UCesiumIonVrtakPanel::RebuildAssetList()
{
	if (!AssetList || !OwnerActor.IsValid())
	{
		return;
	}
	UCesiumIonVrtakSession* Session = OwnerActor->GetSession();
	const bool bReady = Session->IsReady();
	const TArray<FCesiumIonVrtakAsset>& Assets = Session->GetAssets();
	AssetList->ClearChildren();
	AssetsTitle->SetText(FText::FromString(Assets.Num() ? FString::Printf(TEXT("Your assets  (%d)"), Assets.Num()) : TEXT("Your assets")));
	if (!bReady)
	{
		AssetList->AddChildToVerticalBox(MakeText(TEXT("Sign in, and your Cesium ion assets appear here."), 13, Muted));
	}
	int32 Tiles = 0, Imagery = 0, Terrain = 0;
	for (const FCesiumIonVrtakAsset& Asset : Assets)
	{
		Tiles += Asset.Type == TEXT("3DTILES") ? 1 : 0;
		Imagery += Asset.Type == TEXT("IMAGERY") ? 1 : 0;
		Terrain += Asset.Type == TEXT("TERRAIN") ? 1 : 0;
	}
	if (AllChipLabel)
	{
		AllChipLabel->SetText(FText::FromString(FString::Printf(TEXT("All %d"), Assets.Num())));
		TilesChipLabel->SetText(FText::FromString(FString::Printf(TEXT("3D Tiles %d"), Tiles)));
		ImageryChipLabel->SetText(FText::FromString(FString::Printf(TEXT("Imagery %d"), Imagery)));
		TerrainChipLabel->SetText(FText::FromString(FString::Printf(TEXT("Terrain %d"), Terrain)));
		SetChipStyle(AllChip, AllChipLabel, TypeFilter.IsEmpty());
		SetChipStyle(TilesChip, TilesChipLabel, TypeFilter == TEXT("3DTILES"));
		SetChipStyle(ImageryChip, ImageryChipLabel, TypeFilter == TEXT("IMAGERY"));
		SetChipStyle(TerrainChip, TerrainChipLabel, TypeFilter == TEXT("TERRAIN"));
	}
	// What VR-TAK can add first: 3D Tiles, then terrain, then imagery.
	auto Rank = [](const FString& Type)
	{
		return Type == TEXT("3DTILES") ? 0 : Type == TEXT("TERRAIN") ? 1 : Type == TEXT("IMAGERY") ? 2 : 3;
	};
	const FString Query = SearchText.TrimStartAndEnd();
	TArray<FCesiumIonVrtakAsset> Matches;
	for (const FCesiumIonVrtakAsset& Asset : Assets)
	{
		if (!TypeFilter.IsEmpty() && Asset.Type != TypeFilter)
		{
			continue;
		}
		if (!Query.IsEmpty() && !Asset.Name.Contains(Query, ESearchCase::IgnoreCase) && FString::Printf(TEXT("%lld"), Asset.Id) != Query)
		{
			continue;
		}
		Matches.Add(Asset);
	}
	Matches.StableSort([&Rank](const FCesiumIonVrtakAsset& A, const FCesiumIonVrtakAsset& B)
	{
		return Rank(A.Type) < Rank(B.Type);
	});
	const int32 Pages = FMath::Max(1, FMath::DivideAndRoundUp(Matches.Num(), PageSize));
	PageIndex = FMath::Clamp(PageIndex, 0, Pages - 1);
	const int32 First = PageIndex * PageSize;
	const int32 Last = FMath::Min(Matches.Num(), First + PageSize);
	for (int32 Index = First; Index < Last; ++Index)
	{
		AssetList->AddChildToVerticalBox(MakeAssetRow(Matches[Index], OwnerActor->IsLoaded(Matches[Index].Id), false));
	}
	if (bReady && Matches.Num() == 0)
	{
		AssetList->AddChildToVerticalBox(MakeText(Assets.Num() ? TEXT("No assets match.") : TEXT("No assets in this account."), 13, Muted));
	}
	if (PageLabel)
	{
		PageLabel->SetText(FText::FromString(Matches.Num()
			? FString::Printf(TEXT("%d-%d of %d"), First + 1, Last, Matches.Num()) : FString()));
		PrevButton->SetIsEnabled(PageIndex > 0);
		NextButton->SetIsEnabled(PageIndex < Pages - 1);
	}
}

void UCesiumIonVrtakPanel::HandleHide()
{
	if (OwnerActor.IsValid())
	{
		OwnerActor->HidePanel();
	}
}

void UCesiumIonVrtakPanel::HandleSearchChanged(const FText& Value)
{
	SearchText = Value.ToString();
	PageIndex = 0;
	RebuildAssetList();
}

void UCesiumIonVrtakPanel::HandleFilterAll() { SetTypeFilter(FString()); }
void UCesiumIonVrtakPanel::HandleFilterTiles() { SetTypeFilter(TEXT("3DTILES")); }
void UCesiumIonVrtakPanel::HandleFilterImagery() { SetTypeFilter(TEXT("IMAGERY")); }
void UCesiumIonVrtakPanel::HandleFilterTerrain() { SetTypeFilter(TEXT("TERRAIN")); }

void UCesiumIonVrtakPanel::HandlePrevPage()
{
	--PageIndex;
	RebuildAssetList();
}

void UCesiumIonVrtakPanel::HandleNextPage()
{
	++PageIndex;
	RebuildAssetList();
}

void UCesiumIonVrtakPanel::HandleDockLeft() { if (OwnerActor.IsValid()) { OwnerActor->DockPanel(-40.0); } }
void UCesiumIonVrtakPanel::HandleDockFront() { if (OwnerActor.IsValid()) { OwnerActor->DockPanel(0.0); } }
void UCesiumIonVrtakPanel::HandleDockRight() { if (OwnerActor.IsValid()) { OwnerActor->DockPanel(40.0); } }
void UCesiumIonVrtakPanel::HandleSmaller() { if (OwnerActor.IsValid()) { OwnerActor->ScalePanel(0.85f); } }
void UCesiumIonVrtakPanel::HandleBigger() { if (OwnerActor.IsValid()) { OwnerActor->ScalePanel(1.18f); } }

void UCesiumIonVrtakPanel::HandleFollow()
{
	if (OwnerActor.IsValid())
	{
		OwnerActor->ToggleFollowPanel();
		FollowLabel->SetText(FText::FromString(OwnerActor->IsFollowingPanel() ? TEXT("Follow: on") : TEXT("Follow: off")));
	}
}

void UCesiumIonVrtakPanel::GoToAsset(int64 AssetId)
{
	if (OwnerActor.IsValid())
	{
		OwnerActor->GoToAsset(AssetId);
	}
}

void UCesiumIonVrtakPanel::ShowMessage(const FString& Message)
{
	if (MessageText)
	{
		MessageText->SetText(FText::FromString(Message));
	}
}

void UCesiumIonVrtakPanel::ToggleAsset(int64 AssetId, const FString& Name)
{
	if (!OwnerActor.IsValid())
	{
		return;
	}
	for (const FCesiumIonVrtakAsset& Asset : OwnerActor->GetSession()->GetAssets())
	{
		if (Asset.Id == AssetId)
		{
			OwnerActor->ToggleAsset(Asset);
			return;
		}
	}
	// Featured content, or on the map but no longer listed: the type we know, else 3D Tiles.
	FCesiumIonVrtakAsset Unlisted;
	Unlisted.Id = AssetId;
	Unlisted.Name = Name;
	Unlisted.Type = OwnerActor->IsLoaded(AssetId) ? OwnerActor->GetLoadedType(AssetId) : FeaturedType(AssetId);
	OwnerActor->ToggleAsset(Unlisted);
}

void UCesiumIonVrtakPanel::ShowSettings(bool bShow)
{
	if (!SettingsCard)
	{
		return;
	}
	if (bShow)
	{
		LoadForm();
	}
	SettingsCard->SetVisibility(bShow ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);
	SettingsButton->SetBackgroundColor(bShow ? FLinearColor(Blue.R, Blue.G, Blue.B, 0.35f) : Card);
	Refresh();
}

void UCesiumIonVrtakPanel::LoadForm()
{
	if (!OwnerActor.IsValid())
	{
		return;
	}
	UCesiumIonVrtakSession* Session = OwnerActor->GetSession();
	bFormSelfHosted = Session->IsSelfHosted();
	ServerUrlBox->SetText(FText::FromString(Session->IsSelfHosted() ? Session->GetServerUrl() : FString()));
	ApiUrlBox->SetText(FText::FromString(Session->IsSelfHosted() && Session->GetApiUrl() != Session->GetServerUrl() ? Session->GetApiUrl() : FString()));
	SingleUserCheck->SetIsChecked(Session->IsSingleUser());
	UpdateModeButtons();
}

void UCesiumIonVrtakPanel::UpdateModeButtons()
{
	CloudModeButton->SetBackgroundColor(bFormSelfHosted ? Card : Blue);
	CloudModeLabel->SetColorAndOpacity(FSlateColor(bFormSelfHosted ? Text : Ground));
	SelfHostedModeButton->SetBackgroundColor(bFormSelfHosted ? Blue : Card);
	SelfHostedModeLabel->SetColorAndOpacity(FSlateColor(bFormSelfHosted ? Ground : Text));
	SelfHostedFields->SetVisibility(bFormSelfHosted ? ESlateVisibility::Visible : ESlateVisibility::Collapsed);
}

void UCesiumIonVrtakPanel::HandleSignIn()
{
	if (OwnerActor.IsValid())
	{
		OwnerActor->SignInOrOut();
	}
}

void UCesiumIonVrtakPanel::HandleRefresh()
{
	if (OwnerActor.IsValid())
	{
		OwnerActor->RefreshAssets();
	}
}

void UCesiumIonVrtakPanel::HandleSettings()
{
	ShowSettings(SettingsCard->GetVisibility() != ESlateVisibility::Visible);
}

void UCesiumIonVrtakPanel::HandleDone()
{
	ShowSettings(false);
}

void UCesiumIonVrtakPanel::HandleCloudMode()
{
	bFormSelfHosted = false;
	UpdateModeButtons();
}

void UCesiumIonVrtakPanel::HandleSelfHostedMode()
{
	bFormSelfHosted = true;
	UpdateModeButtons();
}

void UCesiumIonVrtakPanel::HandleSave()
{
	if (!OwnerActor.IsValid())
	{
		return;
	}
	const FString Server = ServerUrlBox->GetText().ToString().TrimStartAndEnd();
	if (bFormSelfHosted && !(Server.StartsWith(TEXT("https://")) || Server.StartsWith(TEXT("http://"))))
	{
		ShowMessage(TEXT("Enter the Self-Hosted server's URL, starting with https:// (or http:// on a closed network)."));
		return;
	}
	OwnerActor->SaveServerSettings(bFormSelfHosted, Server, ApiUrlBox->GetText().ToString().TrimStartAndEnd(),
		SingleUserCheck->IsChecked());
}

FString UCesiumIonVrtakPanel::FeaturedName(int64 AssetId)
{
	for (const FFeatured& Each : Featured)
	{
		if (Each.Id == AssetId)
		{
			return Each.Name;
		}
	}
	return FString();
}

FString UCesiumIonVrtakPanel::FeaturedType(int64 AssetId)
{
	for (const FFeatured& Each : Featured)
	{
		if (Each.Id == AssetId)
		{
			return Each.Type;
		}
	}
	return TEXT("3DTILES");
}
