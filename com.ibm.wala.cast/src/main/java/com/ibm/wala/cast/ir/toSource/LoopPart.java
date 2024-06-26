package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.ssa.ISSABasicBlock;
import java.util.Set;

/**
 * A loop part is a set of all nodes in a control-flow cycle (allowing repetitions in the cycle) in
 * the CFG
 *
 * <p>It's read from source AST
 */
public class LoopPart {

  /** Header of the loop */
  private ISSABasicBlock loopHeader;

  /**
   * The conditional branch which will control continue the loop or exit the loop It is usually the
   * first conditional branch in a loop (the last for do loop)
   */
  private ISSABasicBlock loopControl;

  /** All blocks of the loop part */
  private Set<ISSABasicBlock> allBlocks;

  /**
   * The blocks that have one control-edge to a block in the loop and one that is not in the loop
   * This set will contain loop control to ease development
   */
  private Set<ISSABasicBlock> loopBreakers;

  /** The successors of loop breakers that go out of the loop */
  private Set<ISSABasicBlock> loopExits;

  public ISSABasicBlock getLoopHeader() {
    return loopHeader;
  }

  public void setLoopHeader(ISSABasicBlock loopHeader) {
    this.loopHeader = loopHeader;
  }

  public ISSABasicBlock getLoopControl() {
    return loopControl;
  }

  public void setLoopControl(ISSABasicBlock loopControl) {
    this.loopControl = loopControl;
  }

  public Set<ISSABasicBlock> getAllBlocks() {
    return allBlocks;
  }

  public void setAllBlocks(Set<ISSABasicBlock> allBlocks) {
    this.allBlocks = allBlocks;
  }

  public Set<ISSABasicBlock> getLoopBreakers() {
    return loopBreakers;
  }

  public void setLoopBreakers(Set<ISSABasicBlock> loopBreakers) {
    this.loopBreakers = loopBreakers;
  }

  public Set<ISSABasicBlock> getLoopExits() {
    return loopExits;
  }

  public void setLoopExits(Set<ISSABasicBlock> loopExits) {
    this.loopExits = loopExits;
  }
}
