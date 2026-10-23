using UnrealBuildTool;

public class CesiumHostEditorTarget : TargetRules
{
	public CesiumHostEditorTarget(TargetInfo Target) : base(Target)
	{
		Type = TargetType.Editor;
		DefaultBuildSettings = BuildSettingsVersion.V4;
		IncludeOrderVersion = EngineIncludeOrderVersion.Unreal5_3;
		ExtraModuleNames.Add("CesiumHost");
	}
}
