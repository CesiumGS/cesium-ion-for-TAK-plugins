using UnrealBuildTool;

public class VTAK_CesiumIon : ModuleRules
{
	public VTAK_CesiumIon(ReadOnlyTargetRules target) : base(target)
	{
		PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;
		PublicDependencyModuleNames.AddRange(new string[] { "Core", "CoreUObject", "Engine", "Projects" });
		PrivateDependencyModuleNames.AddRange(new string[]
		{
			"UMG", "Slate", "SlateCore", "HTTP", "HTTPServer", "Json", "JsonUtilities",
			// VR-TAK's globe (geodetic <-> Unreal); VH_UI comes with the VR panel
			"VH_Globe", "VH_GlobeMath",
			// Cesium for Unreal
			"CesiumRuntime"
		});
		if (target.Platform == UnrealTargetPlatform.Win64)
		{
			// DPAPI keeps the Cesium ion token encrypted for the Windows user.
			PublicSystemLibraries.Add("Crypt32.lib");
		}
	}
}
