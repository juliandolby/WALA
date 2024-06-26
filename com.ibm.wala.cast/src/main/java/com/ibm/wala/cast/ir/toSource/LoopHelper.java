package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.cast.ir.ssa.AssignInstruction;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.util.collections.IteratorUtil;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The helper class for some methods of loop */
public class LoopHelper {

  /**
   * Determine loop type based on what's in the loop
   *
   * @param cfg The control flow graph
   * @param loop The loop for type
   * @return The loop type
   */
  public static LoopType getLoopType(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg, Loop loop) {
    // TODO: add unsupported loop type
    if (loop.getLoopHeader().equals(loop.getLoopControl())) {
      boolean notWhileLoop = false;

      // If loopHeader and loopControl are the same, check if there are any other instructions
      // before Conditional Branch, if no, it is a while loop
      List<SSAInstruction> headerInsts =
          IteratorUtil.streamify(loop.getLoopHeader().iterator()).collect(Collectors.toList());
      for (SSAInstruction inst : headerInsts) {
        if (inst.iIndex() < 0) continue;
        if (inst instanceof SSAUnaryOpInstruction) {
          continue;
        }
        if (inst instanceof SSABinaryOpInstruction) {
          continue;
        }
        if (inst instanceof SSAConditionalBranchInstruction) {
          continue;
        }
        notWhileLoop = true;
        break;
      }

      if (!notWhileLoop) {
        // check loop exits
        if (loop.getLoopExits().size() > 1) {
          // if all loop exits normal successor are the same, it's while loop
          List<ISSABasicBlock> nextBBs =
              loop.getLoopExits().stream()
                  .map(ex -> cfg.getNormalSuccessors(ex))
                  .flatMap(Collection::stream)
                  .distinct()
                  .collect(Collectors.toList());
          if (nextBBs.size() < 2) {
            return LoopType.WHILE;
          }
        } else {
          return LoopType.WHILE;
        }
      }
    }

    // If loopControl successor is loopHeader then it's a do loop, no matter they are the same block
    // or different
    boolean doLoop = false;
    Iterator<ISSABasicBlock> succ = cfg.getSuccNodes(loop.getLoopControl());
    while (succ.hasNext()) {
      ISSABasicBlock nextBB = succ.next();
      // Find the branch of loop control which will remain in the loop
      if (loop.getAllBlocks().contains(nextBB)) {
        // It should be a goto chunk in this case, otherwise it's whiletrue loop
        List<SSAInstruction> nextInsts =
            IteratorUtil.streamify(nextBB.iterator()).collect(Collectors.toList());
        if (!gotoChunk(nextInsts)) {
          break;
        }

        Iterator<ISSABasicBlock> nextSucc = cfg.getSuccNodes(nextBB);
        while (nextSucc.hasNext()) {
          if (loop.getLoopHeader().equals(nextSucc.next())) {
            doLoop = true;
            break;
          }
        }
      }
    }

    // TODO: check unsupported loop types
    if (doLoop) return LoopType.DOWHILE;
    else return LoopType.WHILETRUE;
  }

  /**
   * @param chunk A list of instructions
   * @return If the chunk of instructions only has goto instruction
   */
  private static boolean gotoChunk(List<SSAInstruction> chunk) {
    return chunk.size() == 1 && chunk.iterator().next() instanceof SSAGotoInstruction;
  }

  /**
   * Find out the loop that contains the instruction
   *
   * @param cfg The control flow graph
   * @param instruction The instruction to be used to look for a loop
   * @param loops All the loops that's in the control flow graph
   * @return The loop that contains the instruction. It can be null if no loop can be found
   */
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

  /**
   * Find out if the given chunk is in the loop and before the conditional branch of the loop
   * control
   *
   * @param cfg The control flow graph
   * @param chunk The instructions to be used to check
   * @param loops All the loops that's in the control flow graph
   * @return True if the given chunk is in the loop and before the conditional branch of the loop
   *     control
   */
  public static boolean beforeLoopControl(
      PrunedCFG<SSAInstruction, ISSABasicBlock> cfg,
      List<SSAInstruction> chunk,
      Map<ISSABasicBlock, Loop> loops) {
    // Find out the first instruction in the chunk
    Optional<SSAInstruction> first = chunk.stream().filter(inst -> inst.iIndex() > 0).findFirst();

    if (!first.isPresent()) {
      return false;
    }

    // Find out the loop
    Loop loop = getLoopByInstruction(cfg, first.get(), loops);
    if (loop == null) return false;

    ISSABasicBlock currentBB = cfg.getBlockForInstruction(first.get().iIndex());
    // If the block is after loop control, return false
    if (currentBB.getNumber() > loop.getLoopControl().getNumber()) {
      return false;
    } else if (currentBB.getNumber() < loop.getLoopControl().getNumber()) {
      // If the block is before loop control, return true
      return true;
    } else {
      // if it is loop control, check if it is not conditional branch
      return !isConditional(chunk) && !isAssignment(chunk);
    }
  }

  // Check if the given chunk contains any instruction that's part of conditional branch
  private static boolean isConditional(List<SSAInstruction> chunk) {
    return chunk.stream().anyMatch(inst -> inst instanceof SSAConditionalBranchInstruction);
  }

  // Check if the given chunk contains any instruction that's an assignment generated by phi node
  private static boolean isAssignment(List<SSAInstruction> chunk) {
    return chunk.stream().allMatch(inst -> inst instanceof AssignInstruction);
  }

  /**
   * Check if the given instruction is part of loop control
   *
   * @param cfg The control flow graph
   * @param inst The instruction to be used to check
   * @param loops All the loops that's in the control flow graph
   * @return True if the given instruction is part of loop control
   */
  public static boolean isLoopControl(
      PrunedCFG<SSAInstruction, ISSABasicBlock> cfg,
      SSAInstruction inst,
      Map<ISSABasicBlock, Loop> loops) {
    return inst.iIndex() > 0
        ? loops.values().stream()
            .map(loop -> loop.getLoopControl())
            .anyMatch(control -> control.equals(cfg.getBlockForInstruction(inst.iIndex())))
        : false;
  }
}
