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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class TargetFileParser {

    public record ParsedTargetFile(List<URI> repoURIs, List<Types.IURef> iuRefs, boolean includeConfigurePhase) {}


    public record IUReference(String id, String version) {}

    /**
     * Parse a .target file, returning:
     *   (1) A list of repository URIs
     *   (2) A list of IUReference (id + optional version)
     */
    public static ParsedTargetFile parseTargetFile(String path) {
        List<URI> repoURIs = new ArrayList<>();
        List<Types.IURef> iuRefs = new ArrayList<>();
        Boolean includeConfigurePhase = null;

        File file = (new File(path)).toPath().toAbsolutePath().toFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("Target definition does not exist: " + file
                    + " (check -targetDefinition; prefer absolute paths)");
        }

        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(file);
            doc.getDocumentElement().normalize();

            // Typically .target has <locations><location> elements
            NodeList locationNodes = doc.getElementsByTagName("location");
            for (int i = 0; i < locationNodes.getLength(); i++) {
                Element locationElem = (Element) locationNodes.item(i);
                if (locationElem == null) continue;

                // PDE only persists includeConfigurePhase on IU bundle containers (TargetDefinitionPersistenceHelper),
                // and IULocationFactory reads it to set IUBundleContainer.INCLUDE_CONFIGURE_PHASE
                // (see eclipse.pde/ui/org.eclipse.pde.core/.../IULocationFactory.java). When the attribute is absent we
                // default to true so fully provisioned installs still run the configure phase even though PDE generally
                // skips it for IDE launches.
                var locationType = locationElem.getAttribute("type");
                if ("InstallableUnit".equalsIgnoreCase(locationType)) {
                    var includeAttr = locationElem.getAttribute("includeConfigurePhase");
                    if (!includeAttr.isBlank()) {
                        includeConfigurePhase = Boolean.parseBoolean(includeAttr);
                    }
                }

                // 1) Repositories
                NodeList repoNodes = locationElem.getElementsByTagName("repository");
                for (int j = 0; j < repoNodes.getLength(); j++) {
                    Element repoElem = (Element) repoNodes.item(j);
                    if (repoElem == null) continue;
                    String repoUriStr = repoElem.getAttribute("location");
                    if (!repoUriStr.isBlank()) {
                        try {
                            repoURIs.add(new URI(repoUriStr));
                        } catch (Exception e) {
                            System.err.println("Invalid repo URI: " + repoUriStr + " (" + e.getMessage() + ")");
                        }
                    }
                }

                // 2) IU references (id + version)
                NodeList unitNodes = locationElem.getElementsByTagName("unit");
                for (int k = 0; k < unitNodes.getLength(); k++) {
                    Element unitElem = (Element) unitNodes.item(k);
                    if (unitElem == null) continue;
                    String iuId = unitElem.getAttribute("id");
                    String iuVersion = unitElem.getAttribute("version");

                    if (!iuId.isBlank()) {
                        // version can be empty
                        if (iuVersion.isBlank()) {
                            iuVersion = null;
                        }
                        iuRefs.add(new Types.IURef(iuId, iuVersion));
                    }
                }
            }

        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse target definition " + file + ": " + e.getMessage(), e);
        }

        return new ParsedTargetFile(repoURIs, iuRefs, includeConfigurePhase == null || includeConfigurePhase);
    }
}
