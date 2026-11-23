// The published files the porter downloads (~/.mmt/mappings: intermediary, Mojang's mappings and jars, the game's
// libraries, Fabric API and loader jars), kept in the Actions cache one entry per file, keyed by its name. The name is
// the artifact's coordinates (intermediary-1.18.2-v2.jar, fabric-api-0.116.18+1.21.1.jar), and a published version never
// changes, so an entry is right for every job that needs it, and each file is stored once however many updates use it.
// Fabric's maven reset connections when a sweep's jobs downloaded at once (4 jobs on 2026-11-23); a job that restores
// its files downloads only what no job kept before.
//
// restore: restores every file this kind of job used last time (~/.mmt/downloads.txt, kept by the action per job).
// save: keeps every file this job downloaded, and writes the list of files it used.
const fs = require('fs');
const os = require('os');
const path = require('path');

const DIR = path.join(os.homedir(), '.mmt', 'mappings');
const LIST = path.join(os.homedir(), '.mmt', 'downloads.txt');
const RESTORED = path.join(os.homedir(), '.mmt', 'downloads-restored.txt');
const KEY = 'download-v1-';
// The lists that change when a release or a build is published (Mojang's version list and each release's version.json,
// which Mojang has edited after release, and the mavens' release lists). Every other file there is one published version.
const CHANGING = /^(version_manifest_v2\.json|version-.+\.json|.+maven-metadata\.xml|yarn-meta-.+\.json)$/;

function published(name) {
  return !CHANGING.test(name) && !name.endsWith('.part');
}

function lines(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').filter(Boolean) : [];
}

async function each(items, width, work) {
  const queue = [...items];
  await Promise.all(Array.from({ length: Math.min(width, queue.length) }, async () => {
    while (queue.length) {
      await work(queue.shift());
    }
  }));
}

exports.restore = async (cache, core) => {
  const wanted = lines(LIST).filter(published);
  fs.mkdirSync(DIR, { recursive: true });
  const restored = [];
  await each(wanted, 8, async (name) => {
    if (await cache.restoreCache([path.join(DIR, name)], KEY + name)) {
      restored.push(name);
    }
  });
  fs.writeFileSync(RESTORED, restored.sort().join('\n'));
  core.notice(`kept downloads: ${restored.length} of the ${wanted.length} files this job used last time restored from the cache`);
};

exports.save = async (cache, core) => {
  const restored = new Set(lines(RESTORED));
  const files = fs.existsSync(DIR) ? fs.readdirSync(DIR).filter((n) => {
    const s = fs.statSync(path.join(DIR, n));
    return s.isFile() && s.size > 0;
  }) : [];
  const used = files.filter(published).sort();
  const downloaded = used.filter((n) => !restored.has(n));
  let kept = 0;
  await each(downloaded, 4, async (name) => {
    try {
      if (await cache.saveCache([path.join(DIR, name)], KEY + name) > 0) {
        kept++;
      }
    } catch (e) {
      core.info(`${name}: not kept (${e.message})`); // another job kept it first
    }
  });
  fs.writeFileSync(LIST, used.join('\n'));
  core.notice(`kept downloads: ${restored.size} published files restored, ${downloaded.length} downloaded by this job (${kept} newly kept), `
    + `${files.length - used.length} release lists downloaded`);
};
