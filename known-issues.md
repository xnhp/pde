
Observed on commit `bf24c6ab0de835470c8029387512b1f94ee9f83a`:
launch resolution: org.knime.base.treeensembles2 seems to not properly receive transitive dependencies
(resolution in IJ workspace seems fine)
mitigated by adding dependency `org.knime.core.ui` explicitly in treeensembles2 MANIFEST.MF


Observed on plugin version `1.6.9-bf24c6ab0de835470c8029387512b1f94ee9f83a`
Libraries from `/lib` subdirectory (as included in `MANIFEST.MF` via `Bundle-ClassPath`)
- ...are not included as IJ dependencies automatically
- ...may override / replace other deps.
  - example: org.knime.base.tests uses `lib/mockito-core-3.12.0.jar` and `lib/mockito-inline-3.12.0.jar`
    but current resolver would add `Partial:org.mockito.mockito-core@2.28.2.v.20231001-knime` as dep


Observed on plugin version `1.6.9-bf24c6ab0de835470c8029387512b1f94ee9f83a`
Module compile output path is sometimes reset / not respected
Happened with `org.knime.base.tests`, would switch to `workshop-intellij-setup` dir
Possibly due to having moved the corresponding `.iml` file.