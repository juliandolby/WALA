package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.cast.ir.ssa.AssignInstruction;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.IteratorUtil;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** The helper class for some methods of loop */
public class LoopHelper {

  public static LoopType getLoopType(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg, Loop loop) {
    boolean notWhileLoop = true;
    if (loop.getLoopHeader().equals(loop.getLoopControl())) {
      // If loopHeader and loopControl are the same, check if there are any other instructions
      // before Conditional Branch, if no, it is a while loop
      notWhileLoop =
          IteratorUtil.streamify(loop.getLoopHeader().iterator())
              .anyMatch(
                  inst ->
                      inst.iIndex() > 0
                          && !(inst instanceof SSAConditionalBranchInstruction
                              || inst instanceof AssignInstruction));
    }

    if (!notWhileLoop) {
      return LoopType.WHILE;
    }

    // If loopControl successor is loopHeader then it's a do loop
    boolean doLoop = false;
    Iterator<ISSABasicBlock> succ = cfg.getSuccNodes(loop.getLoopControl());
    while (succ.hasNext()) {
      // TODO: need to be verified ---lisa
      if (loop.getLoopHeader().equals(succ.next())) {
        doLoop = true;
      }
    }

    if (doLoop) return LoopType.DOWHILE;
    else return LoopType.WHILETRUE;
  }

  public static Loop getLoopByInstruction(
      PrunedCFG<SSAInstruction, ISSABasicBlock> cfg,
      SSAInstruction instruction,
      Map<ISSABasicBlock, Loop> loops) {
    if (instruction.iIndex() < 0) return null;
    Optional<Loop> result =
        loops.values().stream()
            .filter(
                loop ->
                    loop.getAllBlocks().contains(cfg.getBlockForInstruction(instruction.iIndex())))
            .findFirst();
    return result.isPresent() ? result.get() : null;
  }
}
