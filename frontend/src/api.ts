const base=import.meta.env.VITE_API_BASE_URL ?? '';
export class ApiError extends Error { constructor(public status:number,message:string){super(message)} }
export async function api<T>(path:string,init?:RequestInit):Promise<T>{
  const response=await fetch(base+path,init);
  if(!response.ok){let message=`요청 실패 (${response.status})`;try{const error=await response.json();if(typeof error.message==='string')message=error.message;}catch{/* do not expose raw HTML/server traces */}throw new ApiError(response.status,message);}
  return response.json() as Promise<T>;
}
