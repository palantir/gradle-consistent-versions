# 1. `getVersion` resolves a per-project view of the unified classpath

## Context

`getVersion('group:name')` (from `GetVersionPlugin`) lets build scripts look up the version GCV settled on for a dependency. Originally, this worked by resolving the **root** project's `unifiedClasspath` configuration to read the version back out, regardless of which project `getVersion` was called from.

On Gradle 9 this fails when it is called from a subproject:

```
Caused by: org.gradle.api.internal.artifacts.configurations.DefaultConfiguration$IllegalResolutionException:
Resolution of the configuration ':unifiedClasspath' was attempted without an exclusive lock. This is unsafe and not allowed.
        at com.palantir.gradle.versions.GetVersionPlugin.getOptionalVersion(GetVersionPlugin.java:98)
        at com.palantir.gradle.versions.GetVersionPlugin.getVersion(GetVersionPlugin.java:83)
```

This affects any subproject `getVersion` call but commonly surfaces via `sls-packaging`'s `minimumVersion` usage:
```groovy
distribution {
    productDependency {
        productGroup = "com.palantir.group"
        productName = "my-service"
        minimumVersion = getVersion('com.palantir.group', 'my-service-module-api')
        maximumVersion = "1.x.x"
    }
}
```

### Approaches considered

- **Read the `versions.lock` file directly.** The `getVersion('group:name')` lookup could parse the resolved version straight out of `versions.lock`. But `GetVersionPlugin` is as part of GCV itself, it has direct access to the very same resolved dependency model GCV uses to *write* `versions.lock`, so re-parsing its own serialised output would be backwards. It also wouldn't work: the lockfile only records *external* modules, so it couldn't serve a `getVersion` lookup that resolves to another project in the same build.
- **Read the `gcvLocks` constraints instead of resolving.** The above error only applies to *resolution* — reading a configuration's declared `DependencyConstraint`s is fine, so the lookup could read the strict constraints off the root `gcvLocks` platform instead. This works for the common case but still reads the root project's model from a subproject, it needs special-casing for the `getVersion(project(...))` case.
- **Per-project view of the unified graph (chosen).** Each project resolves its *own* configuration instead of reaching into the root's `unifiedClasspath`. This is the isolated-projects-shaped route and is the only one that keeps all `getVersion` overloads working without special-casing.

## Decision

Each project resolves a per-project view of the unified dependency graph instead of reaching into the root's `unifiedClasspath`.

The trick is a **dependency-scope configuration** that both the resolvable (lock-computing) and consumable (per-project) views extend, so no configuration extends one of a different role (Gradle 9 warns on a consumable extending a resolvable, and vice versa).

- **`VersionsLockPlugin`** (root): collects every project's production + test deps into a new dependency-scope configuration, `unifiedClasspathDependencies`. `unifiedClasspath` (resolvable) now just `extendsFrom` it and still computes the lock state — behaviour is unchanged, `unifiedClasspathDependencies` is purely an intermediate.
- **`GetVersionPlugin`** (root): adds `gcvGetVersionElements`, a *consumable* view that also extends `unifiedClasspathDependencies`, carrying capability `gcv:get-versions:0` and the `GCV_SOURCE` usage. Applies `GetVersionProjectPlugin` to every project.
- **`GetVersionProjectPlugin`** (new, per-project): registers a resolvable `gcvGetVersions` configuration that depends on `project(':')` requesting the same capability, and wires the `getVersion(...)` ? to resolve that configuration.

```mermaid
flowchart TD
    subgraph root["root project"]
        deps["<b>unifiedClasspathDependencies</b><br/><i>dependency-scope configuration</i><br/>(every project's prod + test deps)"]
        elements["<b>gcvGetVersionElements</b><br/><i>consumable</i> · cap gcv:get-versions:0"]
        unified["<b>unifiedClasspath</b><br/><i>resolvable</i> → writeLocks / verifyLocks"]
        deps -->|extendsFrom| elements
        deps -->|extendsFrom| unified
    end
    subgraph child[":child (and every project)"]
        getv["<b>gcvGetVersions</b><br/><i>resolvable</i>"]
        gv["getVersion('g:n')<br/><i>reads its own resolution result</i>"]
        getv --> gv
    end
    getv -->|"project(':') + requireCapabilities"| elements

    style root fill:none,stroke:#bbb,color:#111
    style child fill:none,stroke:#bbb,color:#111
```

## Consequences

Because `getVersion` resolves the unified graph in the *calling* project, the result still carries both locked external versions and in-build project versions, and it never resolves a cross-project configuration — so the exclusive-lock error is gone and the `getVersion('group:name', configuration)` / `getVersion(project(...))` cases keep working with no special-casing.
