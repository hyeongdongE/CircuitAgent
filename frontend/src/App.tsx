import {useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {api} from './api';
import type {Component,ImportResult,Issue,NetView,Catalog,Execution,History} from './types';
const root=(id:string)=>`/api/designs/${encodeURIComponent(id)}`;
const errorText=(error:Error|null)=>error?.message ?? '';
function Errors({error}:{error:Error|null}){return error?<p role="alert" className="error">{errorText(error)}</p>:null;}
function Json({value}:{value:unknown}){return <pre>{JSON.stringify(value,null,2)}</pre>;}
function readIds():string[]{try{return JSON.parse(localStorage.getItem('circuitagent.designIds')??'[]').filter((v:unknown)=>typeof v==='string' && /^[0-9a-f-]{36}$/i.test(v));}catch{return [];}}
export default function App(){
  const [ids,setIds]=useState<string[]>(readIds);const [design,setDesign]=useState(ids[0]??'');const [manual,setManual]=useState('');
  function imported(id:string){const next=[id,...ids.filter(x=>x!==id)];setIds(next);localStorage.setItem('circuitagent.designIds',JSON.stringify(next));setDesign(id);}
  return <main><header><span className="eyebrow">CIRCUITAGENT · LOCAL POC</span><h1>회로와 검증 근거</h1><p>공개 PADS QCV와 BOM을 조회하고 정형 Rule의 결과를 확인합니다.</p></header>
    <aside className="notice">로컬 PoC 전용입니다. PASS는 해당 Rule의 명시적 조건에 한정됩니다. UNKNOWN은 설계 오류 판정이 아닙니다. 삼성 QCV·기업 인증·Stage 01 Parser는 아직 미연동입니다.</aside>
    <Upload onImported={imported}/>
    <section><h2>설계 선택</h2><label>저장된 설계<select aria-label="저장된 설계" value={design} onChange={e=>setDesign(e.target.value)}><option value="">설계를 선택하세요</option>{ids.map(id=><option key={id} value={id}>{id}</option>)}</select></label>
      <form className="inline" onSubmit={e=>{e.preventDefault();if(/^[0-9a-f-]{36}$/i.test(manual)){if(!ids.includes(manual))setIds([...ids,manual]);setDesign(manual);}}}><label>설계 ID<input aria-label="설계 ID" value={manual} onChange={e=>setManual(e.target.value)} placeholder="기존 검사 조회용 UUID" pattern="[0-9a-fA-F-]{36}" required/></label><button>설계 열기</button></form>
      {design?<p className="mono" data-testid="active-design">{design}</p>:<p>먼저 파일을 업로드하거나 기존 설계 ID를 입력하세요.</p>}</section>
    {design&&<><Explorer key={'explore-'+design} id={design}/><AuditPanel key={'audit-'+design} id={design}/></>}
    <footer>Net 이름은 측정 전압이 아닙니다. 미실장 연결은 실장된 도통 경로로 해석하지 않습니다.</footer></main>;
}
function Upload({onImported}:{onImported:(id:string)=>void}){
  const [bom,setBom]=useState<File|null>(null),[net,setNet]=useState<File|null>(null),[name,setName]=useState('Sparrow FE'),[version,setVersion]=useState('R3');
  const upload=useMutation({mutationFn:async()=>{if(!bom || !net || !bom.name.toLowerCase().endsWith('.xlsx') || !net.name.toLowerCase().endsWith('.qcv'))throw new Error('BOM XLSX와 QCV 파일을 선택하세요.');const form=new FormData();form.append('name',name);form.append('version',version);form.append('netlistFormat','qcv');form.append('bom',bom);form.append('netlist',net);return api<ImportResult>('/api/designs/import',{method:'POST',body:form});},onSuccess:r=>onImported(r.id)});
  return <section><h2>1. 회로 업로드</h2><form onSubmit={e=>{e.preventDefault();upload.mutate();}} className="grid"><label>설계 이름<input value={name} maxLength={191} required onChange={e=>setName(e.target.value)}/></label><label>설계 버전<input value={version} maxLength={64} required onChange={e=>setVersion(e.target.value)}/></label><label>BOM XLSX<input type="file" accept=".xlsx" onChange={e=>setBom(e.target.files?.[0]??null)} required/></label><label>QCV Netlist<input type="file" accept=".qcv" onChange={e=>setNet(e.target.files?.[0]??null)} required/></label><button disabled={upload.isPending}>{upload.isPending?'업로드 중…':'회로 업로드'}</button></form><Errors error={upload.error}/>
    {upload.data&&<p role="status" data-testid="import-summary">Component {upload.data.componentCount} · Pin {upload.data.pinCount} · Net {upload.data.netCount} · Connection {upload.data.connectionCount} · TERM {upload.data.terminatedPinCount} · Issue {upload.data.issues.length}</p>}
    <p className="muted">버전은 입력값입니다. 두 파일의 설계·Revision 일치 여부를 업로드 전에 확인하세요.</p></section>;
}
function Explorer({id}:{id:string}){
  const [search,setSearch]=useState(''),[ref,setRef]=useState(''),[netId,setNetId]=useState('');
  const components=useQuery({queryKey:['components',id],queryFn:()=>api<Component[]>(root(id)+'/components')});
  const issues=useQuery({queryKey:['issues',id],queryFn:()=>api<Issue[]>(root(id)+'/issues')});
  const selected=useQuery({queryKey:['component',id,ref],queryFn:()=>api<Component>(root(id)+'/components/'+encodeURIComponent(ref)),enabled:!!ref});
  const net=useQuery({queryKey:['net',id,netId],queryFn:()=>api<NetView>(root(id)+'/nets/'+encodeURIComponent(netId)),enabled:!!netId});
  const matches=components.data?.filter(c=>c.reference.toLowerCase().includes(search.toLowerCase()))??[];
  return <section><h2>2. Circuit Explorer</h2><label>Component 검색<input value={search} onChange={e=>setSearch(e.target.value)} placeholder="U16, R107, C5…"/></label>{components.isPending&&<p>부품을 불러오는 중…</p>}<Errors error={components.error}/>
    {components.data&&<p>{matches.length}개 검색 결과 · 최대 50개 표시</p>}<div className="chips">{matches.slice(0,50).map(c=><button key={c.reference} onClick={()=>{setRef(c.reference);setNetId('');}}>{c.reference} · {c.assemblyStatus}</button>)}</div>{components.data&&matches.length===0&&<p>일치하는 부품이 없습니다.</p>}
    {selected.isFetching&&<p>선택 부품 조회 중…</p>}<Errors error={selected.error}/>{selected.data&&<article data-testid="component-detail"><h3>{selected.data.reference} · {selected.data.assemblyStatus}</h3><p>Value: {selected.data.value??'미확인'} · MPN: {selected.data.specification.mpn??'미확인'}</p>
      <div className="scroll"><table><thead><tr><th>속성</th><th>원문</th><th>정규값</th></tr></thead><tbody>{Object.entries(selected.data.specification.attributes).map(([k,v])=><tr key={k}><td>{k}</td><td>{v.raw??'미확인'}</td><td>{v.normalized==null?'—':String(v.normalized)+' '+(v.unit??'')}</td></tr>)}</tbody></table></div>
      <h4>Pin / Net</h4><div className="scroll"><table><thead><tr><th>번호</th><th>이름</th><th>선언 상태</th><th>Net</th></tr></thead><tbody>{selected.data.pins.map(p=><tr key={p.number}><td>{p.number}</td><td>{p.name??'미확인'}</td><td>{p.disposition}</td><td>{p.net?<button onClick={()=>setNetId(p.net!.id)}>{p.net.name}</button>:'연결 Net 없음'}</td></tr>)}</tbody></table></div><details><summary>BOM/QCV 출처</summary><Json value={{sourcePath:selected.data.sourcePath,rawSources:selected.data.rawSources,pinSources:selected.data.pins.map(p=>({number:p.number,source:p.sourceMetadata}))}}/></details></article>}
    {net.isFetching&&<p>Net 조회 중…</p>}<Errors error={net.error}/>{net.data&&<article data-testid="net-detail"><h3>Net: {net.data.name}</h3><p>{net.data.sourcePath}</p><div className="chips">{net.data.connectedPins.map(p=><button key={p.reference+'-'+p.number} onClick={()=>setRef(p.reference)}>{p.reference}-{p.number}</button>)}</div></article>}
    <details><summary>ImportIssue ({issues.data?.length??'조회 중'})</summary><Errors error={issues.error}/>{issues.data?.length===0&&<p>ImportIssue 없음</p>}<div className="scroll"><table><thead><tr><th>코드</th><th>부품</th><th>근거/설명</th></tr></thead><tbody>{issues.data?.map((x,i)=><tr key={i}><td>{x.severity} · {x.code}</td><td>{x.reference??'설계 전체'}</td><td>{x.source}<br/>{x.message}</td></tr>)}</tbody></table></div></details></section>;
}
function AuditPanel({id}:{id:string}){
  const client=useQueryClient(),[selection,setSelection]=useState<string[]|null>(null),[auditId,setAuditId]=useState('');
  const catalog=useQuery({queryKey:['rules'],queryFn:()=>api<Catalog>('/api/audit-rules')});
  const history=useQuery({queryKey:['history',id],queryFn:()=>api<History[]>(root(id)+'/audits')});
  const selectedIds=selection??catalog.data?.rules.map(r=>r.id)??[];
  const audit=useQuery({queryKey:['audit',id,auditId],queryFn:()=>api<Execution>(root(id)+'/audits/'+auditId),enabled:!!auditId});
  const run=useMutation({mutationFn:()=>api<Execution>(root(id)+'/audits',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({schemaVersion:'poc-rule-v1',ruleIds:selectedIds})}),onSuccess:result=>{setAuditId(result.id);client.setQueryData(['audit',id,result.id],result);void client.invalidateQueries({queryKey:['history',id]});}});
  return <section><h2>3. Rule Audit</h2><p>아래 Rule은 Sparrow FE R3에 수동으로 연결한 PoC 입력입니다. 다른 설계에는 대상과 적용 조건을 먼저 확인하세요.</p>{catalog.isPending&&<p>Rule 조회 중…</p>}<Errors error={catalog.error}/>{catalog.data&&<><p className="muted">{catalog.data.scope}</p><div className="rules">{catalog.data.rules.map(r=><label key={r.id}><input type="checkbox" checked={selectedIds.includes(r.id)} onChange={e=>setSelection(e.target.checked?[...selectedIds,r.id]:selectedIds.filter(x=>x!==r.id))}/><span>{r.id}<small>{r.type} · {r.target.reference} · {r.source.revision}<br/>{r.condition}</small></span></label>)}</div></>}
    <button disabled={run.isPending || selectedIds.length===0} onClick={()=>run.mutate()}>{run.isPending?'검증 실행 중…':'Rule 검증 실행'}</button><Errors error={run.error}/><Errors error={audit.error}/>{audit.isFetching&&<p>검증 결과 조회 중…</p>}
    {audit.data&&<article data-testid="audit-results"><h3>검증 결과 · {audit.data.designVersion}</h3><p>{audit.data.executedAt}</p><p className="mono">Audit {audit.data.id}<br/>Snapshot {audit.data.snapshotHash}</p>{audit.data.results.map(r=><div key={r.id} className="result"><h4><span className={'badge '+r.status}>{r.status}</span> {r.ruleId} · {r.reference}</h4><p>계산/조회 값: {r.measured??'미확인'} {r.unit??''}</p><p>{r.reason}</p>{r.missingInputs.length>0&&<><strong>누락 또는 불확실한 입력</strong><ul>{r.missingInputs.map(x=><li key={x}>{x}</li>)}</ul></>}<details><summary>Evidence / 근거</summary><p><a href={r.evidence.datasheet.url} target="_blank" rel="noreferrer">공식/입력 출처</a> · {r.evidence.datasheet.revision} · p{r.evidence.datasheet.page} · {r.evidence.datasheet.section}</p><p>{r.evidence.condition}</p><Json value={{expected:r.expected,...r.evidence}}/></details></div>)}</article>}
    <h2>4. Audit History</h2>{history.isFetching&&<p>검사 이력 조회 중…</p>}<Errors error={history.error}/>{history.data?.length===0&&<p>저장된 검사가 없습니다.</p>}<div className="scroll"><table><thead><tr><th>시간</th><th>버전/결과</th><th>상세</th></tr></thead><tbody>{history.data?.map(h=><tr key={h.id}><td>{h.executedAt}</td><td>{h.designVersion} · {Object.entries(h.counts).map(([k,v])=>`${k} ${v}`).join(' / ')}</td><td><button onClick={()=>setAuditId(h.id)}>검사 상세</button></td></tr>)}</tbody></table></div></section>;
}
