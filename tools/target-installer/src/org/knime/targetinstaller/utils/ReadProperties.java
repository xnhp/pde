/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 */

package org.knime.targetinstaller.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ReadProperties {

    public static Optional<InstallConfiguration> readFromArgs(String[] rawArguments, Path baseDir) {
        if (rawArguments == null || rawArguments.length == 0) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < rawArguments.length; i++) {
            String arg = rawArguments[i];
            if (arg == null || !arg.startsWith("-")) {
                continue;
            }
            if (i + 1 >= rawArguments.length) {
                continue;
            }
            String value = rawArguments[i + 1];
            switch (arg) {
                case "-profileId":
                case "-profile-id":
                    values.put("profileId", value);
                    i++;
                    break;
                case "-p2Path":
                case "-p2-path":
                    values.put("p2Path", value);
                    i++;
                    break;
                case "-targetDefinition":
                case "-target-definition":
                    values.put("targetDefinition", value);
                    i++;
                    break;
                case "-install":
                case "-install-folder":
                    values.put("installFolder", value);
                    i++;
                    break;
                case "-bundlePool":
                case "-bundle-pool":
                    values.put("bundlePool", value);
                    i++;
                    break;
                default:
                    break;
            }
        }
        if (!values.containsKey("profileId")
                || !values.containsKey("p2Path")
                || !values.containsKey("targetDefinition")
                || !values.containsKey("installFolder")
                || !values.containsKey("bundlePool")) {
            return Optional.empty();
        }
        Path resolvedBase = baseDir != null ? baseDir : Paths.get("").toAbsolutePath().normalize();
        return Optional.of(new InstallConfiguration(
                values.get("profileId"),
                resolvePath(resolvedBase, values.get("p2Path")),
                resolvePath(resolvedBase, values.get("targetDefinition")),
                resolvePath(resolvedBase, values.get("installFolder")),
                resolvePath(resolvedBase, values.get("bundlePool"))
        ));
    }

    private static Path resolvePath(Path baseDir, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path raw = Paths.get(value);
        if (raw.isAbsolute() || baseDir == null) {
            return raw.normalize();
        }
        return baseDir.resolve(raw).normalize();
    }

    public record InstallConfiguration(String profileId, Path p2Path, Path targetDefinition, Path installFolder,
                                       Path bundlePool) {

    }

}
