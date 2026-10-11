export type Assembly = 'FITTED' | 'DNP' | 'UNSPECIFIED';
export type Status = 'PASS' | 'FAIL' | 'UNKNOWN' | 'NOT_APPLICABLE' | 'ERROR';
export interface Attribute { raw:string|null; normalized:number|string|null; unit:string|null }
export interface Specification { mpn:string|null; manufacturer:string|null; type:string|null; attributes:Record<string,Attribute> }
export interface Net { id:string; name:string; sourcePath:string|null }
export interface Pin { number:string; name:string|null; electricalType:string|null; disposition:string; net:Net|null; sourceMetadata:Record<string,string> }
export interface Component { reference:string; value:string|null; assemblyStatus:Assembly; specification:Specification; pins:Pin[]; sourcePath:string|null; rawSources:Record<string,Record<string,string>> }
export interface Issue { code:string; severity:string; reference:string|null; source:string|null; message:string }
export interface ImportResult { id:string; componentCount:number; pinCount:number; netCount:number; connectionCount:number; terminatedPinCount:number; issues:Issue[] }
export interface NetView extends Net { connectedPins:{reference:string; number:string}[]; components:string[] }
export interface Rule { id:string; version:string; type:string; target:{reference:string; pinNumber:string|null}; condition:string; source:{url:string; revision:string; section:string; summary:string; page:number; sha256:string} }
export interface Catalog { schemaVersion:string; scope:string; pinMapping:Record<string,string>; rules:Rule[] }
export interface AuditResult { id:string; ruleId:string; reference:string; status:Status; measured:string|null; expected:unknown; unit:string|null; reason:string; missingInputs:string[]; evidence:{datasheet:Rule['source']; condition:string; components:Record<string,unknown>; specifications:Record<string,unknown>; pins:unknown[]; calculation:Record<string,string>; issues:Issue[]}; executedAt:string }
export interface Execution { id:string; designId:string; designVersion:string; snapshotHash:string; ruleSnapshot:Rule[]; results:AuditResult[]; executedAt:string; timingMs:Record<string,number> }
export interface History { id:string; designId:string; designVersion:string; executedAt:string; counts:Partial<Record<Status,number>> }
