package org.zkoss.zss.ngapi.impl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.zkoss.zss.ngmodel.CellRegion;


public class MergeUpdateCollector {
	static ThreadLocal<MergeUpdateCollector>  current = new ThreadLocal<MergeUpdateCollector>();
	
	private Set<MergeUpdate> mergeChanges;
	
	public MergeUpdateCollector(){
	}
	public static MergeUpdateCollector setCurrent(MergeUpdateCollector ctx){
		MergeUpdateCollector old = current.get();
		current.set(ctx);
		return old;
	}
	
	public static MergeUpdateCollector getCurrent(){
		return current.get();
	}
	public void addMergeChange(CellRegion original,CellRegion changeTo) {
		addMergeChange(new MergeUpdate(original, changeTo));
	}
	public void addMergeChange(MergeUpdate mergeUpdate) {
		if(mergeChanges==null){
			mergeChanges = new LinkedHashSet<MergeUpdate>();
		}
		mergeChanges.add(mergeUpdate);
	}
	
	public Set<MergeUpdate> getMergeChanges(){
		return mergeChanges==null?Collections.EMPTY_SET:Collections.unmodifiableSet(mergeChanges);
	}

	public void addMergeChanges(Set<MergeUpdate> mergeChanges) {
		if(this.mergeChanges==null){
			this.mergeChanges = new LinkedHashSet<MergeUpdate>();
		}
		this.mergeChanges.addAll(mergeChanges);
	}
}
