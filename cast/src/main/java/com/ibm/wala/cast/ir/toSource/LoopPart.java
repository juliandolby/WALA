package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.util.collections.Pair;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A loop part is a set of all nodes in a control-flow cycle (allowing repetitions in the cycle) in
 * the CFG
 *
 * <p>It's read from source AST
 */
public class LoopPart<T> {

  /** Header of the loop */
  private T loopHeader;

  /**
   * The conditional branch which will control continue the loop or exit the loop It is usually the
   * first conditional branch in a loop (the last for do loop)
   */
  private T loopControl;

  /** All blocks of the loop part */
  private Set<T> allBlocks;

  /**
   * The blocks that have one control-edge to a block in the loop and one that is not in the loop is
   * called loop breaker This set will contain loop control to ease development The second value in
   * the pair is loop exit Which is the successors of loop breakers that go out of the loop
   */
  private Set<Pair<T, T>> loopBreakers;

  public T getLoopHeader() {
    return loopHeader;
  }

  public void setLoopHeader(T loopHeader) {
    this.loopHeader = loopHeader;
  }

  public T getLoopControl() {
    return loopControl;
  }

  public void setLoopControl(T loopControl) {
    this.loopControl = loopControl;
  }

  public Set<T> getAllBlocks() {
    return allBlocks;
  }

  public void setAllBlocks(Set<T> allBlocks) {
    this.allBlocks = allBlocks;
  }

  public Set<T> getLoopBreakers() {
    assert (loopBreakers != null);
    return loopBreakers.stream().map(pair -> pair.fst).collect(Collectors.toSet());
  }

  public Set<Pair<T, T>> getLoopBreakersExits() {
    assert (loopBreakers != null);
    return loopBreakers;
  }

  public void setLoopBreakers(Set<Pair<T, T>> loopBreakers) {
    this.loopBreakers = loopBreakers;
  }

  public Set<T> getLoopExits() {
    assert (loopBreakers != null);
    return loopBreakers.stream().map(pair -> pair.snd).collect(Collectors.toSet());
  }

  @Override
  public String toString() {
    return "[LoopPart:" + loopHeader + ":" + allBlocks + "]";
  }
}
