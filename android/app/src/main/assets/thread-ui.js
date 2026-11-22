/* Copy button on code blocks. Text goes to the app over a terminus:// link (the clipboard API is not
   reliable in this WebView). Runs once now and again whenever rows are added. */
(function(){
  window.__copyCode=function(t){location.href='terminus://copy-code?t='+encodeURIComponent(t);};
  function add(){
    var p=document.querySelectorAll('pre'),i;
    for(i=0;i<p.length;i++){
      var pre=p[i];
      if(pre._cp)continue;
      pre._cp=1;
      var b=document.createElement('button');
      b.className='cpb';b.type='button';b.textContent='Copy';b.setAttribute('aria-label','Copy code');
      b.onclick=function(ev){
        ev.stopPropagation();
        var c=this.parentNode.querySelector('code'),t=(c||this.parentNode).textContent;
        if(t.length>20000)t=t.slice(0,20000);
        window.__copyCode(t);
      };
      pre.appendChild(b);
    }
  }
  try{new MutationObserver(add).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  add();
})();

/* Sent messages over 30% of the screen get a fade and a centred chevron; tapping the clipped text or the
   chevron expands it, the flipped chevron folds it back. Measured against the real window height, only once the page has
   one (a thread laid out before it is shown has no height yet, which made every message look too tall),
   and re-checked when rows are added or the window resizes: a message that fits loses its link. */
(function(){
  var CHEV='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  function fit(){
    var h=window.innerHeight||0;
    if(h<150)return;
    var cap=h*0.3, b=document.querySelectorAll('.row.user .ubody'),i;
    for(i=0;i<b.length;i++){
      var u=b[i];
      if(u.classList.contains('open'))continue;
      var over=u.scrollHeight>cap+8;
      if(over&&!u._more){
        u.classList.add('clip');
        var m=document.createElement('a');
        m.className='umore';m.innerHTML=CHEV;m.setAttribute('aria-label','Show all');m.setAttribute('role','button');
        var toggle=function(u,m){return function(ev){
          if(ev.target.closest&&ev.target.closest('a[href]'))return;
          ev.stopPropagation();
          var open=u.classList.toggle('open');
          m.classList.toggle('up',open);m.setAttribute('aria-label',open?'Show less':'Show all');
        };}(u,m);
        m.onclick=toggle;
        u._toggle=function(u,t){return function(ev){if(u._more&&!u.classList.contains('open'))t(ev);};}(u,toggle);
        u.addEventListener('click',u._toggle);
        u.parentNode.insertBefore(m,u.nextSibling);
        u._more=m;
      } else if(!over&&u._more){
        u.classList.remove('clip');
        if(u._more.parentNode)u._more.parentNode.removeChild(u._more);
        u._more=null;
      }
    }
  }
  try{new MutationObserver(function(m){for(var i=0;i<m.length;i++){var t=m[i].target;if(t&&t.classList&&t.classList.contains('umore'))return;}fit();}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  window.addEventListener('resize',fit);
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',fit);else fit();
})();

/* Map cards (```map blocks, rendered as .mapcard by ChatMarkdown): Leaflet loads on first use from the app's
   assets; tiles come through the Mac's cache (TerminusMap.tileUrl); the blue dot is the phone's own GPS
   (TerminusMap.requestMyLocation) and never leaves the phone. Tapping a pin opens it in the maps app. */
(function(){
  var B=window.TerminusMap; if(!B)return;
  var loading=false, ready=false, pending=[], meWaiters=[];
  function loadLeaflet(cb){
    if(ready)return cb();
    pending.push(cb); if(loading)return; loading=true;
    var l=document.createElement('link');l.rel='stylesheet';l.href='leaflet/leaflet.css';document.head.appendChild(l);
    var s=document.createElement('script');s.src='leaflet/leaflet.js';
    s.onload=function(){ready=true;var p=pending;pending=[];p.forEach(function(f){try{f();}catch(e){}});};
    document.head.appendChild(s);
  }
  window.__mapMe=function(fix){var w=meWaiters;meWaiters=[];w.forEach(function(f){try{f(fix);}catch(e){}});};
  function draw(el){
    var cfg; try{cfg=JSON.parse(el.getAttribute('data-map'));}catch(e){return;}
    var url=B.tileUrl(); if(!url){el.textContent='Map unavailable: no server set';return;}
    var map=L.map(el,{zoomControl:true,attributionControl:true,scrollWheelZoom:false});
    L.tileLayer(url,{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
    var pts=[];
    function km(m){return m>=1000?(m/1000).toFixed(m>=10000?0:1)+' km':Math.round(m/10)*10+' m';}
    function label(text){var t=document.createElement('span');t.textContent=text;return t;}
    if(cfg.path&&cfg.path.length>1){
      var route=L.polyline(cfg.path,{color:'#2f80ed',weight:5,opacity:.8}).addTo(map);
      if(cfg.pathLabel)route.bindTooltip(label(cfg.pathLabel),{permanent:true,direction:'center',className:'maplabel'});
      cfg.path.forEach(function(p){pts.push(p);});
    }
    (cfg.places||[]).forEach(function(p){
      var m=L.circleMarker([p.lat,p.lng],{radius:8,color:'#b5402a',weight:2,fillColor:'#d97757',fillOpacity:.9}).addTo(map);
      // Names are text, never HTML: a place name can come from a web page the agent read.
      if(p.name){var t=document.createElement('span');t.textContent=p.name;m.bindTooltip(t,{permanent:(cfg.places.length<=3),direction:'top',offset:[0,-8]});}
      m.on('click',function(){B.openPlace(p.lat,p.lng,p.name||'');});
      pts.push([p.lat,p.lng]);
    });
    function fit(){
      if(pts.length>1)map.fitBounds(pts,{padding:[28,28],maxZoom:cfg.zoom||16});
      else if(pts.length===1)map.setView(pts[0],cfg.zoom||15);
      else map.setView([51.5,-0.12],10);
    }
    fit();
    if(cfg.me){
      if(B.canLocate()){
        meWaiters.push(function(fix){
          if(!fix)return;
          L.circle([fix.lat,fix.lng],{radius:fix.accuracy||30,stroke:false,fillColor:'#2f80ed',fillOpacity:.15}).addTo(map);
          L.circleMarker([fix.lat,fix.lng],{radius:7,color:'#fff',weight:2,fillColor:'#2f80ed',fillOpacity:1}).addTo(map).bindTooltip('You');
          if(cfg.line)(cfg.places||[]).forEach(function(p){
            var d=map.distance([fix.lat,fix.lng],[p.lat,p.lng]);
            L.polyline([[fix.lat,fix.lng],[p.lat,p.lng]],{color:'#2f80ed',weight:2,dashArray:'6 6',opacity:.9}).addTo(map)
              .bindTooltip(label(km(d)+' straight line'),{permanent:true,direction:'center',className:'maplabel'});
          });
          pts.push([fix.lat,fix.lng]); fit();
        });
        B.requestMyLocation();
      } else {
        var a=document.createElement('a');a.className='mapask';a.textContent='Show my location';
        a.onclick=function(ev){ev.preventDefault();B.askLocation();};
        el.parentNode.insertBefore(a,el.nextSibling);
      }
    }
  }
  function scan(){
    var c=document.querySelectorAll('.mapcard'),i,todo=[];
    for(i=0;i<c.length;i++){if(!c[i]._map){c[i]._map=1;todo.push(c[i]);}}
    if(todo.length)loadLeaflet(function(){todo.forEach(draw);});
  }
  try{new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan);else scan();
})();

/* Background jobs: when a finish line arrives live, the job's running line above it goes (one line per job). */
(function(){
  function tidy(){
    var f=document.querySelectorAll('.bgline.st-done,.bgline.st-failed'),i;
    for(i=0;i<f.length;i++){
      var d=f[i]; if(d._tidy)continue; d._tidy=1;
      var k=d.getAttribute('data-bgkey'), r=document.querySelectorAll('.bgline.st-run[data-bgkey="'+k+'"]'),j;
      for(j=0;j<r.length;j++){ if(r[j].compareDocumentPosition(d)&Node.DOCUMENT_POSITION_FOLLOWING){
        // The finish inherits the start time, so it lands on the same reply as its start.
        var at=r[j].getAttribute('data-at');if(at)d.setAttribute('data-at',at);
        r[j].parentNode.removeChild(r[j]); break; } }
    }
  }
  try{new MutationObserver(tidy).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',tidy);else tidy();
})();

/* ✕ on a running job line: first tap arms it ("Stop?" for 3 s), the second asks the app to stop the job.
   The line then says "stopping…" until the job's finish line replaces it; a refusal shows briefly. */
(function(){
  function lineOf(x){while(x&&!(x.classList&&x.classList.contains('bgline')))x=x.parentNode;return x;}
  window.__bgStop=function(b){
    if(!b||b._busy)return;
    if(!b._armed){
      b._armed=1;b.textContent='Stop?';b.classList.add('armed');
      b._t=setTimeout(function(){b._armed=0;b.textContent='✕';b.classList.remove('armed');},3000);
      return;
    }
    clearTimeout(b._t);b._armed=0;b._busy=1;b.classList.remove('armed');b.textContent='';
    var l=lineOf(b),m=l&&l.querySelector('.bgmeta');if(m)m.textContent='stopping…';
    try{window.TerminusJobs.stop(b.getAttribute('data-cmd')||'');}catch(e){window.__bgStopped(b.getAttribute('data-cmd')||'',false,'not available');}
  };
  window.__bgStopped=function(cmd,ok,msg){
    var bs=document.querySelectorAll('.bgline.st-run .bgstop'),i;
    for(i=0;i<bs.length;i++){
      var b=bs[i];if(!b._busy||b.getAttribute('data-cmd')!==cmd)continue;
      if(ok)continue;
      var l=lineOf(b),m=l&&l.querySelector('.bgmeta');
      if(m)m.textContent="couldn't stop"+(msg?': '+msg:'');
      (function(b,m){setTimeout(function(){b._busy=0;b.textContent='✕';if(m)m.textContent='running';},5000);})(b,m);
    }
  };
})();

/* Background jobs sit with the reply that started them: a tag on the right of that reply's "Completed ▸" row
   ("⟳ 1 job running", "✓ 1 job · 1m 24s"), the job lines (with their ✕) one tap away under it. A reply
   without steps gets a slim row in the same place. Owner = the latest reply sent at or before the job
   started (rows carry data-ts; a finished job's line carries its start in data-at). A job whose turn is still
   live waits as a plain line until that reply settles. A finish arriving live replaces its start in place. */
(function(){
  var KEY='bgopen', busy=false;
  function opened(){try{return JSON.parse(sessionStorage.getItem(KEY)||'{}');}catch(e){return {};}}
  function remember(k,on){try{var o=opened();if(on)o[k]=1;else delete o[k];sessionStorage.setItem(KEY,JSON.stringify(o));}catch(e){}}
  function ownerRow(x){while(x&&!(x.classList&&x.classList.contains('row')))x=x.parentNode;return x;}
  window.__bgToggle=function(tag,on){
    var st=tag&&tag.closest?tag.closest('.steps'):null;if(!st)return;
    if(on===undefined)on=!st.classList.contains('bgopen');
    st.classList.toggle('bgopen',on);
    var r=ownerRow(st);if(r&&r.id)remember(r.id,on);
  };
  function slot(row){
    var st=row.querySelector('.steps');
    if(!st){
      st=document.createElement('div');st.className='steps bgonly';st.innerHTML='<div class="sum"></div>';
      var act=row.querySelector('.reply-actions');
      if(act&&act.parentNode===row)row.insertBefore(st,act);else row.appendChild(st);
    }
    var jobs=st.querySelector('.bgjobs');
    if(!jobs){
      var sum=st.querySelector('.sum');
      var tag=document.createElement('span');tag.className='bgtag';
      tag.setAttribute('onclick','event.stopPropagation();__bgToggle(this)');
      sum.appendChild(tag);
      jobs=document.createElement('div');jobs.className='bgjobs';
      sum.parentNode.insertBefore(jobs,sum.nextSibling);
      if(row.id&&opened()[row.id])st.classList.add('bgopen');
    }
    return jobs;
  }
  function tagHtml(jobs){
    var c=function(q){return jobs.querySelectorAll(q).length;};
    var n=c('.bgline'),run=c('.bgline.st-run'),failed=c('.bgline.st-failed'),done=c('.bgline.st-done'),stale=c('.bgline.st-stale');
    var stopped=0,f=jobs.querySelectorAll('.bgline.st-failed .bgmeta'),i;
    for(i=0;i<f.length;i++)if(/^stopped/.test(f[i].textContent))stopped++;
    failed-=stopped;
    var word=function(k){return k===1?'1 job':k+' jobs';};
    if(run)return '<span class="bgspin"></span>'+word(run)+' running'+(n>run?' · '+(n-run)+' ended':'');
    if(n===1){
      var m=jobs.querySelector('.bgmeta'),meta=m?m.textContent:'';
      var ic=done?'✓':failed?'✕':stopped?'■':'·';
      return '<span class="bgti">'+ic+'</span>1 job'+(meta?' · '+meta.replace(/</g,'&lt;'):'');
    }
    var parts=[];
    if(done)parts.push(done+' done');if(failed)parts.push(failed+' failed');if(stopped)parts.push(stopped+' stopped');if(stale)parts.push(stale+' no result');
    return '<span class="bgti">'+(failed?'✕':'✓')+'</span>'+word(n)+' · '+parts.join(' · ');
  }
  function place(){
    if(busy||!document.body)return;
    busy=true;
    try{
      var owners=[],os=document.querySelectorAll('.row.assistant[data-ts],#livebox[data-ts]'),i,j;
      for(i=0;i<os.length;i++)owners.push({el:os[i],ts:+os[i].getAttribute('data-ts')||0,
        pending:os[i].id==='livebox'||os[i].classList.contains('live')});
      var l=document.querySelectorAll('.bgline'),lines=[];
      for(i=0;i<l.length;i++)if(!(l[i].parentNode&&l[i].parentNode.classList.contains('bgjobs')))lines.push(l[i]);
      for(i=0;i<lines.length;i++){
        var x=lines[i],key=x.getAttribute('data-bgkey');
        // A finish that arrived live: take its start's place.
        if(!x.classList.contains('st-run')&&key){
          var prev=document.querySelectorAll('.bgjobs .bgline.st-run[data-bgkey="'+key+'"],.bgjobs .bgline.st-stale[data-bgkey="'+key+'"]');
          if(prev.length){var p=prev[0];x.setAttribute('data-at',p.getAttribute('data-at')||'');p.parentNode.replaceChild(x,p);x._tidy=1;continue;}
        }
        var at=+x.getAttribute('data-at')||+x.getAttribute('data-ts')||0,best=-1;
        for(j=0;j<owners.length;j++)if(owners[j].ts<=at&&owners[j].ts>best)best=owners[j].ts;
        if(best<0)continue;
        var own=null;
        for(j=0;j<owners.length;j++)if(owners[j].ts===best&&!owners[j].pending)own=owners[j].el;
        if(!own)continue;
        slot(own).appendChild(x);
      }
      var js=document.querySelectorAll('.bgjobs');
      for(i=0;i<js.length;i++){
        var st=js[i].parentNode,tag=st.querySelector('.sum .bgtag');
        if(!js[i].querySelector('.bgline')){
          if(tag)tag.parentNode.removeChild(tag);
          js[i].parentNode.removeChild(js[i]);
          if(st.classList.contains('bgonly'))st.parentNode.removeChild(st);
          continue;
        }
        var h=tagHtml(js[i]);
        if(tag&&tag._h!==h){tag._h=h;tag.innerHTML=h;}
      }
    }finally{busy=false;}
  }
  window.__bgPlace=place;
  try{new MutationObserver(function(){if(!busy)place();}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',place);else place();
})();

/* Background status chip: "⟳ N running" while any job line is still running, "✓ done" for a few seconds
   after one finishes, gone otherwise. It only counts the lines, so it clears exactly when they resolve.
   Tap: scroll to the newest job line. */
(function(){
  var chip=null, flashUntil=0, seenDone=-1, timer=null;
  function el(){
    if(chip)return chip;
    chip=document.createElement('div');chip.id='bgchip';
    chip.onclick=function(){
      var r=document.querySelectorAll('.bgline.st-run'),l=r.length?r:document.querySelectorAll('.bgline');if(!l.length)return;
      var x=l[l.length-1],st=x.closest?x.closest('.steps'):null;
      if(st&&window.__bgToggle){window.__bgToggle(st.querySelector('.bgtag'),true);}
      x.scrollIntoView({behavior:'smooth',block:'center'});
    };
    document.body.appendChild(chip);return chip;
  }
  var LIMIT=10*60*1000, tick=null;
  /* A running line with no finish after 10 minutes stops counting (it only informs; the job is untouched). */
  function expire(){
    var r=document.querySelectorAll('.bgline.st-run'),i,now=Date.now();
    for(i=0;i<r.length;i++){
      var ts=+r[i].getAttribute('data-ts')||0;
      if(ts&&now-ts>LIMIT){
        r[i].classList.remove('st-run');r[i].classList.add('st-stale');
        var sp=r[i].querySelector('.bgspin');if(sp){var ic=document.createElement('span');ic.className='bgic';ic.textContent='·';sp.parentNode.replaceChild(ic,sp);}
        var mt=r[i].querySelector('.bgmeta');if(mt)mt.textContent='no result seen';
        var st=r[i].querySelector('.bgstop');if(st)st.parentNode.removeChild(st);
      }
    }
  }
  function render(){
    if(!document.body)return;
    expire();
    var run=document.querySelectorAll('.bgline.st-run').length;
    if(run>0&&!tick)tick=setInterval(render,30000); else if(!run&&tick){clearInterval(tick);tick=null;}
    var done=document.querySelectorAll('.bgline.st-done').length;
    if(seenDone>=0&&done>seenDone)flashUntil=Date.now()+4000;
    seenDone=done;
    var c=el();
    if(run>0){c.innerHTML='<span class="bgspin"></span>';c.appendChild(document.createTextNode(run+' running'));}
    else if(Date.now()<flashUntil){c.textContent='✓ done';}
    else {c.className='';document.body.classList.remove('has-bgchip');return;}
    c.className='show';document.body.classList.add('has-bgchip');
    if(!timer&&Date.now()<flashUntil)timer=setTimeout(function(){timer=null;render();},4100);
  }
  try{new MutationObserver(function(m){for(var i=0;i<m.length;i++){if(m[i].target&&m[i].target.id==='bgchip')return;}render();}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',render);else render();
})();
