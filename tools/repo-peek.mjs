const repo = 'Xiaoc7r/DOVideo-AI'
const headers = { 'User-Agent': 'Mozilla/5.0' }
const meta = await fetch(`https://github.com/${repo}`, { headers }).then((r) =>
  r.text()
)
const pick = (re) =>
  (meta.match(re) || [])[1]?.replace(/<[^>]+>/g, '').trim() ?? ''
console.log('TITLE:', pick(/<title>([^<]+)<\/title>/))
console.log(
  'ABOUT:',
  pick(/<span[^>]*itemprop="about"[^>]*>([\s\S]*?)<\/span>/)
)
const langs = [
  ...meta.matchAll(/itemprop="programmingLanguage">([^<]+)</g)
].map((m) => m[1])
console.log('LANGS:', langs.join(', ') || '(none)')
const readme = await fetch(
  `https://raw.githubusercontent.com/${repo}/main/README.md`,
  { headers }
)
  .then((r) =>
    r.ok
      ? r.text()
      : fetch(`https://raw.githubusercontent.com/${repo}/master/README.md`, {
          headers
        }).then((x) => (x.ok ? x.text() : '(no README on main/master)'))
  )
  .catch((e) => 'ERR ' + e.message)
console.log('--- README (first 7000 chars) ---')
console.log(readme.slice(0, 7000))
