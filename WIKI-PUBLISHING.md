# Publishing the GitHub Wiki

The `wiki/` directory is the source of truth for the public LingTong Wiki. GitHub
stores Wiki pages in a separate Git repository, so committing this directory to
the main repository does not publish it automatically.

## First publication

1. Push the main LingTong repository to GitHub.
2. Open repository **Settings > General > Features** and enable **Wikis**.
3. Open the Wiki tab and create the initial Home page once. This initializes the
   separate Wiki repository.
4. Publish the maintained pages:

```shell
git clone https://github.com/01o00o10/lingtong.wiki.git /tmp/lingtong-wiki
cp wiki/*.md /tmp/lingtong-wiki/
git -C /tmp/lingtong-wiki add .
git -C /tmp/lingtong-wiki commit -m "docs: publish LingTong Wiki"
git -C /tmp/lingtong-wiki push
```

Review `git -C /tmp/lingtong-wiki diff --cached` before committing. Do not copy
the private `docs/` directory or test material into the Wiki repository.

## Updating the Wiki

Edit pages under `wiki/` first, review them with the main repository change, and
then repeat the copy, commit, and push steps. Keeping the canonical copy in the
main repository makes Wiki changes reviewable and prevents browser-only edits
from becoming the only version.

