{
  description = "TaskSquire Android app";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-unstable/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      }));

      # Match app/build.gradle (compileSdk) and AGP 8.7's default build tools.
      buildToolsVersion = "34.0.0";

      env = pkgs:
        let
          sdk = (pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "34" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          }).androidsdk;
          sdkRoot = "${sdk}/libexec/android-sdk";
          aapt2 = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
        in
        {
          inherit sdk aapt2;
          vars = {
            JAVA_HOME = "${pkgs.jdk17}";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            # The aapt2 AGP fetches from Maven doesn't run on NixOS; use the SDK's (patched) one.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
          };
        };
    in
    {
      # `nix run` builds the debug APK into app/build/outputs/apk/debug/.
      # Arguments replace the Gradle tasks: `nix run . -- test assembleRelease`.
      packages = forAllSystems (pkgs:
        let e = env pkgs; in rec {
          build = pkgs.writeShellApplication {
            name = "tasksquire-build";
            runtimeInputs = [ pkgs.jdk17 pkgs.git ];
            text = ''
              ${pkgs.lib.concatStrings (pkgs.lib.mapAttrsToList (k: v: "export ${k}=${pkgs.lib.escapeShellArg v}\n") e.vars)}
              # An NDK outside the flake (CI runners preinstall one) would strip native libs and change the APK.
              unset ANDROID_NDK_HOME ANDROID_NDK_ROOT ANDROID_NDK_LATEST_HOME ANDROID_NDK
              if [ ! -x ./gradlew ]; then
                echo "Run this from the tasksquire checkout." >&2
                exit 1
              fi
              # Same as CI: the commit count is the version code, so this APK updates older ones.
              if [ -z "''${VERSION_CODE:-}" ] && count=$(git rev-list --count HEAD 2>/dev/null); then
                export VERSION_CODE=$count
              fi
              if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
              ./gradlew -Pandroid.aapt2FromMavenOverride=${e.aapt2} "$@"
            '';
          };
          default = build;
        });

      apps = forAllSystems (pkgs: rec {
        build = { type = "app"; program = "${self.packages.${pkgs.stdenv.hostPlatform.system}.build}/bin/tasksquire-build"; };
        default = build;
      });

      # `nix develop` for a shell with the same JDK and SDK, to run ./gradlew yourself.
      devShells = forAllSystems (pkgs:
        let e = env pkgs; in {
          default = pkgs.mkShell ({ packages = [ pkgs.jdk17 e.sdk ]; } // e.vars);
        });
    };
}
