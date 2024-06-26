package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.IteratorUtil;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** A loop is a set of nodes in loop parts that share the same loop header */
public class Loop {

  private ISSABasicBlock loopHeader;

  private Set<LoopPart> parts;

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

  public Loop(ISSABasicBlock header) {
    assert (header != null);
    this.loopHeader = header;
    parts = HashSetFactory.make();
  }

  public ISSABasicBlock getLoopHeader() {
    return loopHeader;
  }

  public void addLoopPart(LoopPart part, PrunedCFG<SSAInstruction, ISSABasicBlock> cfg) {
    assert (part != null);
    assert (loopHeader.equals(part.getLoopHeader()));
    parts.add(part);
    mergeLoopParts(cfg);
  }

  private void mergeLoopParts(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg) {
    allBlocks =
        parts.stream()
            .map(part -> part.getAllBlocks())
            .flatMap(Collection::stream)
            .distinct()
            .sorted(
                (a, b) -> {
                  return a.getFirstInstructionIndex() - b.getFirstInstructionIndex();
                })
            .collect(Collectors.toSet());

    Set<ISSABasicBlock> breakers =
        parts.stream()
            .map(part -> part.getLoopBreakers())
            .flatMap(Collection::stream)
            .distinct()
            .collect(Collectors.toSet());

    loopBreakers = HashSetFactory.make(breakers);
    breakers.forEach(
        breaker -> {
          if (allBlocks.containsAll(
              IteratorUtil.streamify(cfg.getSuccNodes(breaker)).collect(Collectors.toList()))) {
            loopBreakers.remove(breaker);
          }
        });

    Set<ISSABasicBlock> exits =
        parts.stream()
            .map(part -> part.getLoopExits())
            .flatMap(Collection::stream)
            .distinct()
            .collect(Collectors.toSet());
    loopExits = exits.stream().filter(ex -> !allBlocks.contains(ex)).collect(Collectors.toSet());

    loopControl = loopBreakers.stream().min(Comparator.comparing(ISSABasicBlock::getNumber)).get();
  }

  public ISSABasicBlock getLoopControl() {
    assert (parts.size() > 0);
    return loopControl;
  }

  public Set<ISSABasicBlock> getLoopBreakers() {
    assert (parts.size() > 0);
    return loopBreakers;
  }

  public Set<ISSABasicBlock> getAllBlocks() {
    assert (parts.size() > 0);
    return allBlocks;
  }

  public Set<ISSABasicBlock> getLoopExits() {
    assert (parts.size() > 0);
    return loopExits;
  }
}
