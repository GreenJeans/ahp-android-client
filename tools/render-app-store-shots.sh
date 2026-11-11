#!/usr/bin/env bash
# Renders framed App Store screenshots from raw simulator captures using headless Chrome.
#
#   tools/render-app-store-shots.sh
#
# Input:  store-assets/app-store/raw/{iphone,ipad}/NN-slug.png   (simulator screenshots)
# Output: store-assets/app-store/iphone-6.9/NN-slug.png  (1320x2868)
#         store-assets/app-store/ipad-13/NN-slug.png     (2064x2752)
# Captions live in the CAPTIONS table below, keyed by slug.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/store-assets/app-store"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# slug|pill|headline line 1|headline line 2 (accent)|subhead
CAPTIONS=$(cat <<'EOF'
live-agent-chat|Live coding agent sessions|Your Coding Agents,|Live in Your Pocket|Stream transcripts, tool calls and terminals straight from your own machine — no cloud relay.
tool-approvals|Face ID approvals|Approve Tool Calls|With a Glance|See the risk and the exact diff, then approve or deny with Face ID.
chat-switcher|Forks, side chats & sub-agents|Every Thread,|One Switcher|Jump between the main chat, forks, side chats and sub-agents as they work.
changesets-and-diffs|Changesets & diffs|Review Every Change|Before It Lands|Per-chat changesets with full file diffs, right on your phone.
mcp-skills-and-agents|MCP servers, skills & agents|Tune Your Agents|From Anywhere|See and control MCP servers, skills, instructions and agents on each host.
sessions-and-inbox|Sessions & inbox|Every Session,|At a Glance|See which agents need you first, then search, filter and jump right in.
EOF
)

render() { # kind width height
  local kind="$1" W="$2" H="$3" out="$ASSETS/$4"
  mkdir -p "$out"
  local n=0
  for raw in "$ASSETS/raw/$kind"/*.png; do
    [[ -e "$raw" ]] || continue
    local base slug line pill h1 h2 sub
    base="$(basename "$raw" .png)"; slug="${base#*-}"
    line="$(grep "^$slug|" <<<"$CAPTIONS" || true)"
    [[ -n "$line" ]] || { echo "no caption for $slug, skipping"; continue; }
    IFS='|' read -r _ pill h1 h2 sub <<<"$line"
    n=$((n+1))
    # Device image box: leave ~27% of height for the caption block.
    local scale fr ir; if [[ "$kind" == iphone ]]; then scale=1 fr=0.045 ir=0.038; else scale=1.35 fr=0.026 ir=0.018; fi
    cat > "$WORK/$base.html" <<HTML
<!doctype html><html><head><meta charset="utf-8"><style>
*{margin:0;box-sizing:border-box}
html,body{width:${W}px;height:${H}px;overflow:hidden}
body{font-family:-apple-system,"SF Pro Display","Helvetica Neue",sans-serif;color:#fff;
 background:radial-gradient(120% 60% at 50% 0%,#17324a 0%,#0c1622 55%,#070c12 100%);
 display:flex;flex-direction:column;align-items:center;padding:calc(${H}px*0.035) 0 calc(${H}px*0.03)}
.pill{font-size:calc(34px*${scale});font-weight:700;letter-spacing:.06em;text-transform:uppercase;
 padding:.45em 1.1em;border:2px solid #2f5d86;border-radius:999px;background:#0f2234;color:#cfe6ff}
.pill b{display:inline-block;width:.55em;height:.55em;border-radius:50%;background:#3fa4ff;margin-right:.6em;vertical-align:.08em}
h1{font-size:calc(104px*${scale});line-height:1.08;font-weight:800;text-align:center;margin-top:.45em;letter-spacing:-.01em}
h1 span{color:#4aa8ff;display:block}
p{font-size:calc(42px*${scale});line-height:1.35;color:#c4d3e2;text-align:center;max-width:86%;margin-top:.55em}
.dev{margin-top:calc(${H}px*0.035);flex:1;min-height:0;display:flex;align-items:flex-start;justify-content:center;width:100%}
.frame{height:100%;padding:calc(${H}px*0.008);border-radius:calc(${H}px*${fr});
 background:linear-gradient(160deg,#3a4b5c,#18222c);box-shadow:0 0 0 3px #2b6b9a,0 40px 120px rgba(0,0,0,.6)}
.frame img{height:100%;display:block;border-radius:calc(${H}px*${ir})}
</style></head><body>
<div class="pill"><b></b>${n} · ${pill}</div>
<h1>${h1}<span>${h2}</span></h1>
<p>${sub}</p>
<div class="dev"><div class="frame"><img src="file://${raw}"></div></div>
</body></html>
HTML
    "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --allow-file-access-from-files --window-size="${W},${H}" \
      --screenshot="$out/$base.png" "file://$WORK/$base.html" >/dev/null 2>&1
    echo "  $out/$base.png"
  done
}

render iphone 1320 2868 iphone-6.9
render ipad 2064 2752 ipad-13
