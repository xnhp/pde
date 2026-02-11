package org.knime.targetinstaller.utils;

import org.eclipse.equinox.app.IApplicationContext;
import org.tinylog.Logger;

import java.util.Arrays;
import java.util.Map;

public class Arguments {
    private boolean dryRun = false;

    public Arguments(IApplicationContext context) {
        // for whatever reason we do not simply receive a couple of strings but have to do this arcane casting first.
        // based on org.eclipse.equinox.p2.internal.repository.tools.MirrorApplication.start
        var arguments = Arrays.stream((String[]) ((Map<?, ?>) context.getArguments()).get(IApplicationContext.APPLICATION_ARGS)).toList();
        Logger.trace("Found CLI arguments: {}", arguments);
        if (arguments.stream().anyMatch(arg -> arg.contains("dry-run"))) {
            this.dryRun = true;
        }

    }

    public boolean isDryRun() {
        return dryRun;
    }
}
