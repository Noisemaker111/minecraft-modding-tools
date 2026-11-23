"""Where the jar scoreboard reads and keeps things, and the numbers a run is sized by: every module here reads them, none is read by them."""
import os
import re
from pathlib import Path
import port_tools  # noqa: E402

ROOT = port_tools.ROOT
# Gigabytes of heap for the Java tools; lower it when other work shares the machine.
HEAP = int(os.environ.get("MMT_HEAP_GB") or 10)
HOME = Path(os.environ.get("MMT_EXPOSURE_HOME") or Path(os.path.expanduser("~")) / ".mmt" / "exposure")
# --verify: port every mod and ask Exposure every reference as if nothing were cached, and fail where a cached answer with
# the same key differs. Main's sweep runs so, with the answers of main's sweep before it, as the check that the cache is exact.
VERIFY = False
AGENT = {"User-Agent": "modding-accelerator-exposure/1.0"}
INDEX = ROOT / "coverage" / "changes" / "index.json"
PINS = ROOT / "bench" / "exposure" / "mods"
COUNT = 150
# The first release each loader's mods are read for, in Mojang's names: Fabric's through intermediary before 26.1,
# NeoForge's (its first release is 1.20.2) through NeoForm's SRG ids before 1.20.5, as shipped after.
FIRST = {"fabric": "1.14.4", "neoforge": "1.20.2"}
UNOBFUSCATED = "26.1"
MOJANG_NEOFORGE = "1.20.5"


# What a route map is built from: the learner and the name tables (tools/port/learn, tools/port/names), not the jar porter.
MAP_SOURCES = ("learn", "names")


KEY = re.compile(r"^[0-9a-f]{16}$")


MAPPINGS = Path(os.path.expanduser("~")) / ".mmt" / "mappings"  # VersionMap.cache: where the tools keep what they download
# The loaders' release lists. The tools take the newest loader, API and loader build of a release from these, so they are
# inputs of every map and port; each is kept once downloaded (VersionMap.fetch), here as in the tools.
PLATFORM = {
    "neoforge-maven-metadata.xml": "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
    "fabric-loader-maven-metadata.xml": "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml",
}


ASM = [ROOT / a for a in port_tools.ASM]


CLASSES = {}  # this run's porter: read once, so an edit made while a sweep runs does not mix two porters in it


# Each process that reads jars (ModNames; JarPort ports on every processor in one process) starts cold and reads a
# release first, so more piles stop paying: when JarPort ran in piles, 150 NeoForge mods took 144 s in one pile on 24
# processors, 66 s in four and 75 s in nine.
PILE = 36
# A pile only pays with processors to spare for its start: on GitHub's 4-processor runners four piles took 104 s
# where one had taken about 90.
SPARE = 4
# Processors this run may use; all runs every update side by side and gives each its share.
CORES = os.cpu_count() or 1


# What reading a jar in Mojang's names is built from: the name tables (tools/port/names).
NAME_SOURCES = ("names",)


STOPS = {}


FABRIC_APIS = {}
