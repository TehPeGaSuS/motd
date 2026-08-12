# motd brand assets

The `/motd` wordmark uses **Roboto Bold 700**, converted to filled outlines so
the SVGs and Android vector render identically without an installed font. This
is the same lettering the app itself shows in the title bar, server drawer, and
About screen, where the wordmark is plain bold platform typography rather than a
stylized asset. Symbol lockups omit the leading slash because the speech-bubble
symbol already supplies the slash-command cue.

Source pin:

- Project: [Roboto](https://github.com/googlefonts/roboto-3-classic)
- Release: `v3.015` (`Roboto_v3.015.zip`)
- Font: `unhinted/static/Roboto-Bold.ttf`
- Font SHA-256: `f02bf7a9869c58b9bb61130f55c6b1a489ea85f2ac1de75b3421dcb133bc57f3`
- Style: Bold, weight 700
- License: Apache License 2.0; see [ROBOTO-LICENSE.txt](ROBOTO-LICENSE.txt)

The final assets contain glyph outlines only; the font binary is not vendored.
Use the pinned font and FontTools `pens.svgPathPen` when regenerating lettering,
then preserve the current 2048-unit em advances (`m` 1774, `o` 1156, `t` 693,
`d` 1153, `/` 760) and the 0.048828125 outline scale.
