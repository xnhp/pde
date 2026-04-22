# Configuration

## Check setup

Verify `pde target install` succeeds.

If you do not have local sources (i.e. `bundles` has no entries), `pde compile` is not needed.

## Configure dev server application launch

In `pde.yaml`, configure a `launches` entry like below. Adjust the path in `-workflowContextConfig` to point to your config file.

```
  - name: GatewayDevServer  # this can be anything, referenced by e.g. `pde run`
    application: com.knime.gateway.executor.GATEWAY_DEV_SERVER_APPLICATION
    programArgs:
      # configuration for the dev server
      - -workflowContextConfig=/home/ben/Desktop/issues/workflowContextConfig.yaml
      # port the gateway dev server will serve on. This is connected to by the frontend.
      - -port=7000
      # optional
      - -clean
    vmArgs:
      - -Dorg.knime.ui.dev.mode=true
      # port the frontend is served on
      - -Dorg.knime.ui.dev.url=http://localhost:3000
      # general sysprops
      - -Dorg.knime.ui.disable_healthchecker=true
      - -XX:CompileCommand=exclude,javax/swing/text/GlyphView,getBreakSpot
      - -Dknime.enable.fastload=true
      - -Dknime.xml.disable_external_entities=true
      - -Dorg.eclipse.ecf.provider.filetransfer.excludeContributors=org.eclipse.ecf.provider.filetransfer.httpclientjava
      - -Dorg.apache.cxf.bus.factory=org.knime.cxf.core.fragment.KNIMECXFBusFactory
      - -Dorg.apache.cxf.transport.http.forceURLConnection=true
      - -Dcomm.disable_dynamic_service=true
      - -Djdk.http.auth.tunneling.disabledSchemes=""
      - -Xmx2048m
      - -Declipse.pde.launch=true
      - --add-modules=ALL-SYSTEM
      - -Djava.security.manager=allow
      - -XX:+ShowCodeDetailsInExceptionMessages
      - -Dfile.encoding=UTF-8
      - -Dstdout.encoding=UTF-8
      - -Dstderr.encoding=UTF-8
```

...where `workflowContextConfig.yaml` has contents as described [in this example](https://github.com/knime/knime-com-gateway/blob/master/com.knime.gateway.executor/src/eclipse/com/knime/gateway/executor/dev/workflowContextConfig.yaml.example).

See [this readme](https://github.com/knime/knime-com-gateway/tree/master/com.knime.gateway.executor/README.md) for details.

## Configure Frontend launch


Get a checkout of `knime/knime-ui`. In `knime-ui/org.knime.ui.js`, run `pnpm i` to install dependencies.

Set up `knime-ui/org.knime.ui.js/.env` as described by `knime-ui/org.knime.ui.js/.env.example`, see details  [here](https://github.com/knime/knime-ui/blob/master/org.knime.ui.js/README.md). Notably, `VITE_BROWSER_DEV_WS_URL` should point to `localhost:7000`, the port you have configured in the gateway dev server `launch` entry:
```
VITE_BROWSER_DEV_HTTP_URL="http://localhost:7000"
```

# Launch

## Launch server

Launch with `pde run GatewayDevServer`.

The following warnings seem not problematic and can be ignored for the time being
```
[WARN] Using lowercase profile registry path: /home/ben/Desktop/issues/master/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile
[WARN] Using lowercase profile registry path: /home/ben/Desktop/issues/master/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile
[WARN] Launch plan has unresolved bundles/dependencies; continuing anyway.
Launch Plan:
  - [MISSING_BUNDLE] org.eclipse.equinox.ds: startup-level
  - [MISSING_BUNDLE] org.eclipse.m2e.logback.configuration: startup-level
CompileCommand: exclude javax/swing/text/GlyphView.getBreakSpot bool exclude = true
WARNING: Using incubator modules: jdk.incubator.vector
```

The server is ready when you see `<PRESS ANY KEY TO TERMINATE>` being logged on stdout.

## Launch frontend

In `knime-ui/org.knime.ui.js`, run `pnpm run dev` to start a vite dev server serving the frontend. This frontend, in turn, will connect to the gateway dev server backend.

## Use

Point your browser at the vite dev server URL, default is `http://localhost:3000/`. You should now see the full editor UI and the loaded workflow.

# Troubleshooting

If you see the editor loading skeleton (spinning KNIME logo), check the browser console.
```
[vite] connecting... client:733:9
[vite] connected. client:827:12
info Injecting global logger browser.mjs:48:7
[Vue Router warn]: No match found for location with path "/" vue-router.mjs:51:18
[...]
Firefox can’t establish a connection to the server at ws://localhost:7000/.
```

This means the frontend could not connect to the backend gateway server at localhost:7000. Check whether server is really running and on what port.