# JPEXS Free Flash Decompiler - Using Gradle

This is a modified version of the original JPEXS Free Flash Decompiler that uses Gradle instead of Ant. The original
repository is [here](https://github.com/jindrapetrik/jpexs-decompiler/).

Other important differences:

- Most of the README was removed on this version to avoid confusion with the original JPEX.
- Netbeans configurations have been removed because Gradle is IDE agnostic.

## Source code

### How to get the source

You can make a local copy of the sources with the following command:

```
git clone https://github.com/unit73e/jpexs-decompiler.git
```

This assumes you have git installed on your system.

### Gradle

To run application, execute task "run" by entering this command:

```
./gradlew run
```

To only build, execute build task:

```
./gradlew build
```

### Building libraries

There are few libraries which need to be built too. These libraries are placed in "libsrc" directory.

* **FFDec_lib** - core of decompilation, SWF parsing, exporting
* **jpacker** - used for compression of JavaScript Canvas scripts (Netbeans/Ant project)
* **jpproxy** - proxy part of FFDec (Netbeans/Ant project)
* **jsyntaxpane** - code editor (Netbeans/Apache Maven project)
* **LZMA** - used for SWF compression (Netbeans/Ant project)
* **nellymoser** - used for Nelly Moser sounds decoding (Netbeans/Ant project)
* **Swf2Exe** - Stub for "Save to EXE" feature (Delphi 7 Project)
* **ttf** - used for TTF font export (Netbeans/Ant project)
* **gnujpdf** - used for PDF export (Netbeans/Ant project)

Some of the libraries are currently being referenced by the built jar due to technical difficulties.

## Change log

All notable changes are listed in the file [CHANGELOG.md](CHANGELOG.md)

## Versioning

Versions are in format `x.y.z`, for example `9.1.2`.

## Authors

The decompiler was originally written by **Jindra Petřík** also known as **JPEXS**.
The application was made in Czech Republic.

### Developers

* **JPEXS** - leader, development of the decompiler, website main admin, github account admin, organization
* **honfika** - development of the decompiler
* **Paolo Cancedda** - former developer
* ...other pushers on GitHub or Google Code

### Translators

* **Jaume Badiella Aguilera** - catalan translation
* **Capasha** - swedish translation
* **王晨旭** (Chenxu Wang) - chinese translation
* **focus** - russian translation
* **honfika** - hungarian translation
* **kalip** - italian translation
* **Krock** - german translation
* **Laurent LOUVET** - french translation
* **MaGiC** - portuguese translation
* **martinkoza** - polish translation
* **Osman ÖZ** - turkish translation
* **pepka** - ukrainian and dutch translation
* **poxyran** - spanish translation
* **realmaster42** - portuguese-brasil translation
* **Rtsjx** - chinese translation
* **koiru** - japanese translation

## Licenses + Acknowledgments

### Application

FFDec Application is licensed under the GNU GPL v3 (GPL-3.0-or-later) licence, see the [license.txt](license.txt).
It uses modified code of these libraries:

* [JSyntaxPane] (Code editor) - Apache License 2.0
* [Muffin] (Proxy) - GPL

And links also these libraries:

* [Java Native Access - JNA] (Registry association, Process memory reading) - LGPL
* [Insubstantial] (Substance Look and Feel, Flamingo Ribbon component) - Revised BSD
* [javactivex] (Flash Player ActiveX embedding) - LGPLv3
* [flashdebugger library] (Debugging ActionScript) - LGPLv3
* FFDec Library (LGPLv3) - see below

Application uses also some icons of the [Silk icons pack], [Silk companion 1] and [FatCow icons pack].

### Library

FFDec Library is licensed under GNU LGPL v3 (LGPL-3.0-or-later), see [license.txt](libsrc/ffdec_lib/license.txt) for details.
It uses modified code of these libraries:

* [sfntly] (WOFF font export) - Apache License 2.0
* [JLayer] (Decoding MP3) - LGPL
* UAB "DKD" NellyMoser ASAO codec (Decoding Nelly Moser sound format) - LGPL
* [Animated GIF Writer] (Frames to GIF export) - Creative Commons Attribution 3.0 Unported
* [Animated GIF Encoder] (Frames to GIF export)

And also links to these libraries:

* [LZMA SDK] (SWF de/compress) - public domain
* [Monte Media Library] (Frames to AVI export) - LGPL
* [Fontastic] (Font TTF export) - LGPL
* [DoubleType] (Font TTF export) - GPLv2
* [jPacker] (Canvas scripts compression) - MIT License
* [gnujpdf] (PDF export) - LGPL License
* [DDSReader] (DDS reading) - MIT License

[GIT]: http://git-scm.com/downloads
[Netbeans IDE]: http://www.netbeans.org/
[Apache Ant]: http://ant.apache.org/
[launch4j]: http://launch4j.sourceforge.net/
[NSIS]: http://nsis.sourceforge.net/
[JSyntaxPane]: https://code.google.com/p/jsyntaxpane/
[Muffin]: https://web.archive.org/web/20171025082558/http://muffin.doit.org/ (original: http://muffin.doit.org/)
[Java Native Access - JNA]: https://github.com/twall/jna
[Insubstantial]: http://shemnon.com/speling/2011/04/insubstantial-62-release.html
[javactivex]:https://github.com/jindrapetrik/javactivex
[flashdebugger library]: https://github.com/jindrapetrik/flashdebugger
[Silk icons pack]: http://www.famfamfam.com/lab/icons/silk/
[Silk companion 1]: http://damieng.com/creative/icons/silk-companion-1-icons
[FatCow icons pack]: http://www.fatcow.com/free-icons
[sfntly]: https://code.google.com/p/sfntly/
[JLayer]: http://www.javazoom.net/javalayer/javalayer.html
[Animated GIF Writer]: http://elliot.kroo.net/software/java/GifSequenceWriter/
[Animated GIF Encoder]: http://www.fmsware.com/stuff/gif.html
[LZMA SDK]: http://www.7-zip.org/sdk.html
[Monte Media Library]: http://www.randelshofer.ch/monte/
[Fontastic]: http://code.andreaskoller.com/libraries/fontastic/
[DoubleType]: http://sourceforge.net/projects/doubletype/
[jPacker]: https://code.google.com/p/jpacker/
[gnujpdf]: http://gnujpdf.sourceforge.net/
[DDSReader]: https://github.com/npedotnet/DDSReader/
