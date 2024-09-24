{
  description = "Local network";

  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system};
      in {
        devShells.default = with pkgs;
          mkShell {
            buildInputs = [ python312 python312Packages.pip gcc13 ];
            shellHook = ''
              sh ./dev-setup.sh
              source .venv/bin/activate
            '';
          };
      });
}
