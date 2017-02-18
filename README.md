# Boot2nix [![Clojars Project](https://img.shields.io/clojars/v/griff/boot2nix.svg)](https://clojars.org/griff/boot2nix)

Boot task  to generate project-info.json for use with nix's Maven repository generation functions.

## Usage

```
(set-env! :dependencies '[[griff/boot2nix "X.Y.Z" :scope "test"]])
(require '[griff/boot2nix :refer :all])
```

First build a local repo cache

```
$ BOOT_LOCAL_REPO=repo boot release
```

```
$ boot boot2nix -l repo
...
wrote project-info.json
```

## Snapshot dependencies

Currently snapshow dependencies are not supported.


## License

Copyright © 2017 Brian Olsen

Based on code made by Juho Teperi

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
