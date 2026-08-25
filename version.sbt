// Set to the release version for a tagged build. There is no sbt-dynver here,
// so this is deliberate bookkeeping: it must match the vX.Y.Z tag, because it
// names the jar that ships inside the image.
ThisBuild / version := "0.2.1"
