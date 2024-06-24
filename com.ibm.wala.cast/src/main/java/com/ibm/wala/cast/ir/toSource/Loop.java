package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.util.collections.HashSetFactory;
import java.util.Set;

/** A loop is a set of nodes in loop parts that share the same loop header */
public class Loop {

  private ISSABasicBlock loopHeader;

  private Set<LoopPart> parts;

  public Loop(ISSABasicBlock header) {
    assert (header != null);
    this.loopHeader = header;
    parts = HashSetFactory.make();
  }

  public ISSABasicBlock getLoopHeader() {
    return loopHeader;
  }

  public void addLoopPart(LoopPart part) {
    assert (part != null);
    assert (loopHeader.equals(part.getLoopHeader()));
    parts.add(part);
  }

  public ISSABasicBlock getLoopControl() {
    assert (parts.size() > 0);
    // TODO: only support one loop part for now
    return parts.iterator().next().getLoopControl();
  }

  public Set<ISSABasicBlock> getLoopBreakers() {
    assert (parts.size() > 0);
    // TODO: only support one loop part for now
    return parts.iterator().next().getLoopBreakers();
  }

  public Set<ISSABasicBlock> getAllBlocks() {
    assert (parts.size() > 0);
    // TODO: only support one loop part for now
    return parts.iterator().next().getAllBlocks();
  }

  public Set<ISSABasicBlock> getLoopExits() {
    assert (parts.size() > 0);
    // TODO: only support one loop part for now
    return parts.iterator().next().getLoopExits();
  }
}
