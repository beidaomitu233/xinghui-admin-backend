package com.xinghuiTec.ai.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.*;
import java.io.IOException;
import java.util.*;

/** Data adapter for the official Skill repository contract. Execution stays in HarnessAgent. */
final class ManagedSkillRepository implements AgentSkillRepository {
    private final ClasspathSkillRepository builtins;
    private final Map<String,AgentSkill> skills=new LinkedHashMap<>();
    ManagedSkillRepository(List<AgentSkill> managed)throws IOException{
        builtins=new ClasspathSkillRepository("hub-skills");
        builtins.getAllSkills().forEach(skill->skills.put(skill.getName(),skill));
        managed.forEach(skill->skills.put(skill.getName(),skill));
    }
    public AgentSkill getSkill(String name){
        var value=skills.get(name);
        if(value==null)value=skills.values().stream().filter(s->s.getSkillId().equals(name)).findFirst().orElse(null);
        if(value==null)throw new IllegalArgumentException("Skill not found");return value;
    }
    public List<String> getAllSkillNames(){return List.copyOf(skills.keySet());}
    public List<AgentSkill> getAllSkills(){return List.copyOf(skills.values());}
    public boolean skillExists(String name){return skills.containsKey(name);}
    public boolean save(List<AgentSkill> value,boolean force){return false;}
    public boolean delete(String name){return false;}
    public AgentSkillRepositoryInfo getRepositoryInfo(){return new AgentSkillRepositoryInfo("application","tenant-skills",false);}
    public String getSource(){return "application";}
    public void setWriteable(boolean value){}
    public boolean isWriteable(){return false;}
    public void close(){builtins.close();}
}
