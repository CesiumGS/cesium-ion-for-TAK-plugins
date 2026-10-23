using UnrealBuildTool;

public class CesiumHostTarget : TargetRules
{
	public CesiumHostTarget(TargetInfo Target) : base(Target)
	{
		Type = TargetType.Game;
		DefaultBuildSettings = BuildSettingsVersion.V4;
		IncludeOrderVersion = EngineIncludeOrderVersion.Unreal5_3;
		ExtraModuleNames.Add("CesiumHost");
	}
}
