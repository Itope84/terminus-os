window.__pbc=window.__pbc||{};
(function(){
var P=window.__pbc,KEY='pbui';
try{var s0=window.sessionStorage.getItem(KEY);if(s0){var o0=JSON.parse(s0);for(var k0 in o0)if(!(k0 in P))P[k0]=o0[k0];}}catch(e){}
function save(){try{window.sessionStorage.setItem(KEY,JSON.stringify(P));}catch(e){}}
function mk(tag,cls,txt){var e=document.createElement(tag);if(cls)e.className=cls;if(txt!=null)e.textContent=txt;return e;}
function setText(e,t){if(e.textContent!==t)e.textContent=t;}
window.__pbt=function(h){var c=h.parentNode;c.classList.toggle('pbc');P.card=c.classList.contains('pbc');save();};
document.addEventListener('click',function(ev){var h=ev.target.closest&&ev.target.closest('.pbhead');if(h)window.__pbt(h);});
window.__pbx=function(r,id){var e=r.parentNode;if(!e.querySelector('.pbfilm'))return;var v=!e.classList.contains('pbo');e.classList.toggle('pbo',v);P[id]=v;save();};
function film(el,t){
  var sig=t.sh.map(function(s){return s.at+':'+s.t;}).join('|');
  var c0=el._figs=el._figs||{};
  function fill(s){var g0=c0[s.at],i0=g0&&g0.firstChild;if(s.img&&i0&&!i0.getAttribute('src'))i0.src='data:image/jpeg;base64,'+s.img;}
  if(el._sig===sig){t.sh.forEach(fill);return;}
  el._sig=sig;
  var f=el.querySelector('.pbfilm');
  if(!f){f=mk('div','pbfilm');el.appendChild(f);}
  var sl=f.scrollLeft,cache=el._figs=el._figs||{},keep={},z,s,g,ar;
  while(f.firstChild)f.removeChild(f.firstChild);
  for(z=0;z<t.sh.length;z++){
    s=t.sh[z];
    if(z){ar=mk('span','pbarr','→');f.appendChild(ar);}
    g=cache[s.at];
    if(!g){
      g=mk('figure');
      var im=document.createElement('img');if(s.img)im.src='data:image/jpeg;base64,'+s.img;g.appendChild(im);
      var cp=mk('figcaption',null,s.t);cp.appendChild(mk('span',null,new Date(s.at).toTimeString().slice(0,8)));g.appendChild(cp);
      cache[s.at]=g;
    }
    else fill(s);
    keep[s.at]=1;
    f.appendChild(g);
  }
  for(var key in cache)if(!keep[key])delete cache[key];
  f.scrollLeft=sl;
}
function step(t,base,j,fs,d){
  var id='s'+t.id,has=t.sh.length>0,open=has&&(id in P?P[id]:j===fs),el=d._have[id];
  if(!el){
    el=mk('div','pbstep');el.setAttribute('data-sid',id);
    var r=mk('div','pbrow'),ic=mk('i','pbi'),nm=mk('span','pbnum'),tx=mk('span','pbtx');
    tx.appendChild(document.createTextNode(''));
    r.appendChild(ic);r.appendChild(nm);r.appendChild(tx);
    r.onclick=function(){window.__pbx(r,id);};
    el.appendChild(r);
    el._r={r:r,ic:ic,nm:nm,tx:tx,sm:null,tm:null,sc:null};
  }
  var x=el._r,cls='pbi '+t.s,glyph=t.s==='o'?'✓':t.s==='x'?'✕':t.s==='h'?'!':'';
  if(x.ic.className!==cls)x.ic.className=cls;
  setText(x.ic,glyph);
  setText(x.nm,String(base+j+1));
  if(x.tx.firstChild.nodeValue!==t.t)x.tx.firstChild.nodeValue=t.t;
  if(t.d){if(!x.sm){x.sm=mk('small');x.tx.appendChild(x.sm);}setText(x.sm,t.d);}
  else if(x.sm){x.tx.removeChild(x.sm);x.sm=null;}
  if(t.ms>0){if(!x.tm){x.tm=mk('em');x.r.insertBefore(x.tm,x.sc);}setText(x.tm,(t.ms/1000).toFixed(t.ms<10000?1:0)+'s');}
  else if(x.tm){x.r.removeChild(x.tm);x.tm=null;}
  if(has){
    if(!x.sc){x.sc=mk('u','pbsc','⌄');x.r.appendChild(x.sc);}
    film(el,t);
  }else if(x.sc){x.r.removeChild(x.sc);x.sc=null;var ff=el.querySelector('.pbfilm');if(ff)el.removeChild(ff);el._sig=null;el._figs={};}
  if(el.classList.contains('pbo')!==open)el.classList.toggle('pbo',open);
  return el;
}
window.__pbh=function(){
  var c=document.querySelectorAll('.pbcard[data-f]'),i;
  for(i=0;i<c.length;i++){
    var k=c[i];if(k._h)continue;k._h=1;
    try{
      var h=k.getAttribute('data-f'),u=new Uint8Array(h.length>>1),j;
      for(j=0;j<u.length;j++)u[j]=parseInt(h.substr(j*2,2),16);
      var o=JSON.parse(new TextDecoder().decode(u)),d=k.querySelector('.pbsteps'),base=o.n-o.steps.length;
      d._have={};d.textContent='';
      if(base>0)d.appendChild(mk('span','pbmore','+'+base+' earlier steps'));
      for(j=0;j<o.steps.length;j++){var t=o.steps[j];t.sh=[];d.appendChild(step(t,base,j,-1,d));}
      setText(k.querySelector('.pbstage'),o.n>0?'Ended · '+o.n+(o.n===1?' step':' steps'):'Ended');
    }catch(e){}
  }
};
try{new MutationObserver(function(){window.__pbh();}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
document.addEventListener('DOMContentLoaded',function(){window.__pbh();});
window.__pbh();
window.__pb=function(o){
  var c=[].filter.call(document.querySelectorAll('.pbcard'),function(e){return !e.hasAttribute('data-f');}),i;
  for(i=0;i<c.length;i++){
    var k=c[i],q=function(s){return k.querySelector(s);},d=q('.pbsteps');
    if(i!==c.length-1||!o){
      if(!k._ended){
        d.textContent='';d._have=null;q('.pbnote').textContent='';
        k.classList.add('pbidle');k.classList.add('pbc');
        setText(q('.pbstage'),'Ended');setText(q('.pblive'),'');
        k._ended=1;
      }
      continue;
    }
    k._ended=0;
    k.classList.remove('pbidle');
    if(k.classList.contains('pbc')!==!!P.card)k.classList.toggle('pbc',!!P.card);
    setText(q('.pblive'),o.live?'Live':'');
    setText(q('.pbstage'),(o.status==='Working'||o.status==='Live'?'In progress':o.status)+' · '+o.n+(o.n===1?' step':' steps'));
    var have={},ch,j;
    ch=[].slice.call(d.children);
    for(j=0;j<ch.length;j++){var sid=ch[j].getAttribute('data-sid');if(sid)have[sid]=ch[j];}
    d._have=have;
    var fs=-1;
    for(j=0;j<o.steps.length;j++)if(o.steps[j].sh.length)fs=j;
    var base=o.n-o.steps.length,pos=0,used={};
    var more=d.querySelector('.pbmore');
    if(base>0){
      if(!more)more=mk('span','pbmore');
      setText(more,'+'+base+' earlier steps');
      if(d.children[0]!==more)d.insertBefore(more,d.firstChild);
      pos=1;
    }else if(more)d.removeChild(more);
    for(j=0;j<o.steps.length;j++){
      var t=o.steps[j],el=step(t,base,j,fs,d);
      used['s'+t.id]=1;
      if(d.children[pos]!==el)d.insertBefore(el,d.children[pos]||null);
      pos++;
    }
    for(var sk in have)if(!used[sk]&&have[sk].parentNode===d)d.removeChild(have[sk]);
    setText(q('.pbnote'),o.note?'📝 '+o.note:'');
    setText(q('.pbfootmsg'),o.live?'Browser is open':'Session ended');
    setText(q('.pburl'),o.url.replace(/^https?:\/\//,''));
    var th=q('.pbthumb');
    if(o.thumb===undefined){}
    else if(o.thumb){if(th._v!==o.thumb){th._v=o.thumb;th.src='data:image/jpeg;base64,'+o.thumb;}}
    else if(th.hasAttribute('src')){th._v='';th.removeAttribute('src');}
  }
};
})();
