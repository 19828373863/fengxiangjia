export const KEY='fengxiangjia.web.v1';
export const goals={stuffy:'Freshen a stuffy room',draft:'Soften a direct draft',breeze:'Bring a breeze to my spot'};
export const walls=['none','top','right','bottom','left'];
export function initial(){return {version:1,goal:'breeze',room:{width:4.5,depth:3.5,window:'top',door:'bottom',targetX:.65,targetY:.55},trials:[]};}
const finite=(v,a,b)=>typeof v==='number'&&Number.isFinite(v)&&v>=a&&v<=b;
export function validRoom(r){return r&&finite(r.width,2,15)&&finite(r.depth,2,15)&&walls.includes(r.window)&&walls.includes(r.door)&&finite(r.targetX,.1,.9)&&finite(r.targetY,.1,.9);}
export function validate(s){
 if(!s||s.version!==1||!validRoom(s.room)||!Object.hasOwn(goals,s.goal)||!Array.isArray(s.trials)||s.trials.length>100)throw Error('This file is not a supported FengXiangJia web backup. Your current records have not changed.');
 const ids=new Set();
 for(const t of s.trials){if(!t||typeof t.id!=='string'||t.id.length>100||ids.has(t.id)||!validRoom(t.room)||!Object.hasOwn(goals,t.goal)||!['better','same','worse',null].includes(t.feedback)||typeof t.created!=='string'||!Number.isFinite(Date.parse(t.created)))throw Error('A trial in this backup is invalid. Your records have not changed.');ids.add(t.id);}
 return {version:1,goal:s.goal,room:{...s.room},trials:s.trials.map(t=>({id:t.id,created:t.created,goal:t.goal,room:{...t.room},feedback:t.feedback}))};
}
export function signature(s){return JSON.stringify([s.goal,s.room]);}
const point=w=>({top:{x:.5,y:.1},right:{x:.9,y:.5},bottom:{x:.5,y:.9},left:{x:.1,y:.5}}[w]);
export function recommend(s){
 if(!validRoom(s.room)||!Object.hasOwn(goals,s.goal))throw Error('Check your room dimensions and target position.');
 const r=s.room,target={x:r.targetX,y:r.targetY};let fan,aim,title,steps,reason;
 if(s.goal==='stuffy'&&r.window!=='none'){
  fan=point(r.window);aim={...fan};
  if(r.window==='top'){fan={x:.5,y:.24};aim.y=0;}if(r.window==='bottom'){fan={x:.5,y:.76};aim.y=1;}if(r.window==='left'){fan={x:.24,y:.5};aim.x=0;}if(r.window==='right'){fan={x:.76,y:.5};aim.x=1;}
  title='Try an outward-facing fan';steps=['Open the selected window if outdoor air is suitable.','Place the fan inside, facing toward that window.',r.door==='none'?'A second safe opening may help provide replacement air.':'Open the selected door if it leads to a safe source of replacement air.'];reason='An outward-facing fan can help move indoor air toward an opening. The result depends on the replacement-air path and outdoor conditions.';
 }else if(s.goal==='stuffy'){
  fan={x:.2,y:.75};aim={x:.75,y:.25};title='Start with gentle circulation';steps=['Place the fan clear of furniture.','Aim across the room, away from your face.','To bring in fresh air, you will need a suitable open window or another ventilation source.'];reason='With no exterior window selected, this arrangement only circulates indoor air. It does not provide fresh-air ventilation.';
 }else if(s.goal==='draft'){
  fan={x:target.x>.5?.2:.8,y:target.y>.5?.2:.8};aim={x:target.x>.5?.85:.15,y:fan.y};title='Move the breeze off your body';steps=['Place the fan to the side of your usual spot.','Aim across the room rather than directly at your face.','Start on a low setting; try oscillation if available.'];reason='Moving the direct jet away from your seat can soften the sensation while keeping air moving nearby.';
 }else{
  fan={x:target.x>.5?.2:.8,y:target.y>.5?.2:.8};aim=target;title='Aim a gentle breeze at your spot';steps=['Move the fan to the marked position, clear of obstacles.','Point it toward your chosen spot.','Start low, then adjust the angle or setting for comfort.'];reason='A clear path from the fan to your seat makes a direct breeze more likely. Furniture and the fan itself can change the result.';
 }
 return {signature:signature(s),fan,aim,target,title,steps,reason,assumption:'Placement guide · not a measured airflow simulation'};
}
export function saveTrial(s,id,created){if(s.trials.length>=100)throw Error('Your history holds 100 trials. Export a backup and delete a trial before adding another.');return validate({...s,trials:[{id,created,goal:s.goal,room:{...s.room},feedback:null},...s.trials]});}
export function feedback(s,id,value){if(!['better','same','worse'].includes(value))throw Error('Choose a feedback option.');if(!s.trials.some(t=>t.id===id))throw Error('This trial no longer exists.');return {...s,trials:s.trials.map(t=>t.id===id?{...t,feedback:value}:t)};}
