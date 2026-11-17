(() => {
 'use strict';
 const $=s=>document.querySelector(s);
 const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const labels={MEMBER:'Membro',DIRECTOR:'Diretoria',ADMIN:'Administrador'};
 const actor=$('meta[name=current-user]').content;
 let directory={users:[],groups:[]}; let editing=false; let selected=null; let selectedRole=null;
 const role=user=>user.groups.some(g=>g.displayName===directory.adminGroup)?'ADMIN':user.groups.some(g=>g.displayName===directory.directorGroup)?'DIRECTOR':'MEMBER';
 async function request(url,method='GET',body) {
  const headers={'Accept':'application/json'};
  if(body) headers['Content-Type']='application/json';
  if(method!=='GET') headers[$('meta[name=csrf-header]').content]=$('meta[name=csrf-token]').content;
  let response;
  try { response=await fetch(url,{method,headers,body:body?JSON.stringify(body):undefined,credentials:'same-origin'}); }
  catch(_) {throw new Error('Sem conexão com o servidor.');}
  if(response.status===401){location.assign('/login');throw new Error('Entre novamente.');}
  if(!response.ok){const error=await response.json().catch(()=>({}));throw new Error(error.message||'Não foi possível concluir a operação. Confira suas permissões e tente novamente.');}
  return response.status===204||response.status===201?null:response.json();
 }
 function fail(selector,error){$(selector).textContent=error.message;$(selector).hidden=false;}
 async function load(){
  directory=await request('/api/admin/directory');
  $('#new-user').disabled=!directory.configured;
  $('#directory-status').textContent=directory.configured?`${directory.users.length} contas no diretório.`:'Conexão administrativa não configurada. Consulte o responsável pela implantação.';
  $('#user-rows').innerHTML=directory.users.length?directory.users.map(user=>`<tr><td><strong>${escape(user.displayName||user.id)}</strong><small>${escape(user.id)}</small></td><td>${escape(user.email)}</td><td><label class="visually-hidden" for="role-${escape(user.id)}">Papel de ${escape(user.id)}</label><select id="role-${escape(user.id)}" data-user-role="${escape(user.id)}" ${user.id===actor?'disabled':''}>${Object.entries(labels).map(([key,label])=>`<option value="${key}" ${key===role(user)?'selected':''}>${label}</option>`).join('')}</select></td><td><div class="admin-row-actions"><button class="button quiet" type="button" data-edit-user="${escape(user.id)}">Editar</button><button class="button quiet" type="button" data-apply-role="${escape(user.id)}" ${user.id===actor?'disabled':''}>Aplicar papel</button><button class="text-button" type="button" data-remove-user="${escape(user.id)}" ${user.id===actor?'disabled':''}>Excluir</button></div></td></tr>`).join(''):'<tr><td colspan="4" class="empty-row">Nenhuma conta disponível.</td></tr>';
  $('#audit-rows').innerHTML=(directory.audit||[]).map(event=>`<tr><td>${escape(new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'short',timeZone:'America/Sao_Paulo'}).format(new Date(event.OCCURRED_AT)))}</td><td>${escape(event.ACTOR)}</td><td>${escape(event.TARGET)}</td><td>${escape(event.OPERATION)}</td></tr>`).join('')||'<tr><td colspan="4" class="empty-row">Nenhuma alteração registrada por este módulo.</td></tr>';
 }
 async function mutated(dialog,message){
  $(dialog).close();$('#toast').textContent=message;$('#toast').hidden=false;setTimeout(()=>$('#toast').hidden=true,4000);
  try{await load();$('#admin-feedback').hidden=true;}catch(error){fail('#admin-feedback',error);}
 }
 $('#new-user').addEventListener('click',()=>{editing=false;$('#user-form').reset();$('#user-form').elements.id.readOnly=false;$('#user-error').hidden=true;$('#user-dialog-title').textContent='Novo usuário';$('#user-dialog').showModal();});
 document.addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button)return;
  if(button.hasAttribute('data-close'))button.closest('dialog').close();
  if(button.dataset.editUser){const user=directory.users.find(u=>u.id===button.dataset.editUser);editing=true;const fields=$('#user-form').elements;fields.id.value=user.id;fields.id.readOnly=true;fields.displayName.value=user.displayName;fields.email.value=user.email;$('#user-dialog-title').textContent='Editar usuário';$('#user-error').hidden=true;$('#user-dialog').showModal();}
  if(button.dataset.applyRole){selected=button.dataset.applyRole;selectedRole=document.getElementById(`role-${selected}`).value;$('#role-summary').textContent=`Alterar o papel de ${selected} para ${labels[selectedRole]}?`;$('#role-error').hidden=true;$('#role-dialog').showModal();}
  if(button.dataset.removeUser){selected=button.dataset.removeUser;$('#remove-user-summary').textContent=`Excluir a conta ${selected} do diretório compartilhado?`;$('#remove-user-error').hidden=true;$('#remove-user-dialog').showModal();}
 });
 $('#user-form').addEventListener('submit',async event=>{event.preventDefault();const fields=event.currentTarget.elements;const button=event.currentTarget.querySelector('[type=submit]');button.disabled=true;try{const body={displayName:fields.displayName.value.trim(),email:fields.email.value.trim()};if(!editing)body.id=fields.id.value.trim();await request(editing?`/api/admin/users/${encodeURIComponent(fields.id.value)}`:'/api/admin/users',editing?'PUT':'POST',body);await mutated('#user-dialog','Usuário salvo no diretório.');}catch(error){fail('#user-error',error);}finally{button.disabled=false;}});
 $('#role-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.currentTarget.querySelector('[type=submit]');button.disabled=true;try{await request(`/api/admin/users/${encodeURIComponent(selected)}/role`,'PUT',{role:selectedRole});await mutated('#role-dialog','Papel atualizado.');}catch(error){fail('#role-error',error);try{await load();}catch(_){/* Keep original error visible. */}}finally{button.disabled=false;}});
 $('#remove-user-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.currentTarget.querySelector('[type=submit]');button.disabled=true;try{await request(`/api/admin/users/${encodeURIComponent(selected)}`,'DELETE');await mutated('#remove-user-dialog','Usuário excluído do diretório.');}catch(error){fail('#remove-user-error',error);}finally{button.disabled=false;}});
 const theme=$('#theme');theme.value=document.documentElement.dataset.themePreference||'system';theme.addEventListener('change',()=>{const value=theme.value;if(value==='system')delete document.documentElement.dataset.mode;else document.documentElement.dataset.mode=value;try{localStorage.setItem('ieee-hub-theme',value);}catch(_){}});
 load().catch(error=>{fail('#admin-feedback',error);$('#directory-status').textContent='Diretório indisponível.';});
})();
