/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

/** 独立维护内置管理控制台，避免把静态页面资源混入运行时生命周期代码。 */
final class ManagementDashboard {
  private static final String HEAD =
      "<!doctype html><html><head><meta charset=utf-8>"
          + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
          + "<title>LingTong Console</title>"
          + "<style>body{margin:0;font:14px system-ui;background:#f4f6f8;color:#17202a}"
          + "header{background:#17202a;color:white;padding:16px 24px;display:flex;"
          + "align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}"
          + "main{max-width:1180px;margin:auto;padding:20px}"
          + "nav{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:16px}"
          + "button,input{height:36px;border:1px solid #aeb6bf;background:white;"
          + "padding:0 12px}button{cursor:pointer}"
          + "button.active{background:#117864;color:white;border-color:#117864}"
          + "#auth{display:flex;gap:8px;max-width:100%}#auth input{min-width:0}"
          + "pre{background:#fff;border:1px solid #d5d8dc;padding:16px;min-height:360px;"
          + "overflow:auto;white-space:pre-wrap}"
          + "h1{font-size:20px;margin:0;letter-spacing:0}.bad{color:#b03a2e}"
          + "</style></head>";

  private static final String BODY =
      "<body><header><h1>LingTong Runtime Console</h1><div id=auth>"
          + "<input id=token type=password placeholder=\"Management token\">"
          + "<button onclick=load()>Connect</button></div></header>"
          + "<main><nav><button data-view=runtime class=active>Runtime</button>"
          + "<button data-view=cluster>Cluster</button>"
          + "<button data-view=diagnostics>Diagnostics</button>"
          + "<button data-view=threads>Threads</button>"
          + "<button data-view=audit>Audit</button>"
          + "<button data-view=metrics>Metrics</button>"
          + "<button onclick=drain()>Drain</button></nav>"
          + "<pre id=output>Enter the management token.</pre></main>";

  private static final String SCRIPT =
      "<script>let view='runtime';"
          + "document.querySelectorAll('nav button[data-view]').forEach(b=>b.onclick=()=>{"
          + "document.querySelector('.active').classList.remove('active');"
          + "b.classList.add('active');view=b.dataset.view;load()});"
          + "function token(){let t=document.getElementById('token').value||"
          + "sessionStorage.ltToken;if(t)sessionStorage.ltToken=t;return t}"
          + "async function load(){let t=token();if(!t)return;"
          + "let o=document.getElementById('output');try{"
          + "let r=await fetch('/__lingtong/manage/v1/'+view,{headers:{Authorization:"
          + "'Bearer '+t}});let x=await r.text();"
          + "if(!r.ok)throw Error(r.status+' '+x);o.className='';"
          + "o.textContent=view==='metrics'?x:JSON.stringify(JSON.parse(x),null,2)}"
          + "catch(e){o.className='bad';o.textContent=e.message}}"
          + "async function drain(){let t=token();"
          + "if(!t||!confirm('Stop accepting new application requests?'))return;"
          + "let r=await fetch('/__lingtong/manage/v1/actions/drain',{method:'POST',"
          + "headers:{Authorization:'Bearer '+t,'X-LingTong-Confirm':'drain'}});"
          + "document.getElementById('output').textContent=await r.text();view='runtime'}"
          + "setInterval(()=>{if(view==='runtime'||view==='cluster')load()},5000)"
          + "</script></body></html>";

  private static final String HTML = HEAD + BODY + SCRIPT;

  private ManagementDashboard() {}

  static String html() {
    return HTML;
  }
}
