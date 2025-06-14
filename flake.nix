{
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-24.05";
  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };
    in {
      devShell = pkgs.mkShell {
        packages = with pkgs; [
          graalvm-ce
          babashka
          (clojure.override {jdk = graalvm-ce;})
          grafana-loki
          grafana
          promtail
          sops
        ];
      };
    });
}
