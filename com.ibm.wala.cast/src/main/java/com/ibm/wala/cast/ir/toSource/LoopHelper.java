package com.ibm.wala.cast.ir.toSource;

import com.ibm.wala.cast.ir.ssa.AssignInstruction;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SSAUnspecifiedExprInstruction;
import com.ibm.wala.util.collections.IteratorUtil;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The helper class for some methods of loop */
public class LoopHelper {

  private static boolean isForLoop(Loop loop) {
    if (loop.getLoopHeader().equals(loop.getLoopControl()) && loop.getLoopBreakers().size() < 2) {
      // A for-loop is targeting for PERFORM n TIMES for now
      // The loopHeader and loopControl are the same
      // The loopHeader should contains 3 or more than 3 instructions (based on current samples)
      // The last 3 instructions in loopHeader should follow a rule on both type and relationship
      // And the second last instruction in loop body should be incremental
      List<SSAInstruction> headerInsts =
          IteratorUtil.streamify(loop.getLoopHeader().iterator()).collect(Collectors.toList());

      if (headerInsts.size() > 2
          && headerInsts.get(headerInsts.size() - 3) instanceof SSAPhiInstruction
          && headerInsts.get(headerInsts.size() - 2) instanceof SSABinaryOpInstruction
          && headerInsts.get(headerInsts.size() - 1) instanceof SSAConditionalBranchInstruction) {
        // The target of first Inst should be the val1 of the next one
        int phiResult = ((SSAPhiInstruction) headerInsts.get(headerInsts.size() - 3)).getDef();
        int opResult = ((SSABinaryOpInstruction) headerInsts.get(headerInsts.size() - 2)).getDef();

        if (((SSABinaryOpInstruction) headerInsts.get(headerInsts.size() - 2)).getUse(0)
                == phiResult
            && ((SSAConditionalBranchInstruction) headerInsts.get(headerInsts.size() - 1)).getUse(0)
                == opResult) {
          List<SSAInstruction> lastInsts =
              IteratorUtil.streamify(loop.getLastBlock().iterator()).collect(Collectors.toList());
          if (lastInsts.size() > 1) {
            SSAInstruction lastOp = lastInsts.get(lastInsts.size() - 2);
            if (lastOp instanceof SSABinaryOpInstruction) {
              return ((SSAPhiInstruction) headerInsts.get(headerInsts.size() - 3)).getUse(0)
                  == ((SSABinaryOpInstruction) lastOp).getDef();
            }
          }
        }
      }
    }
    return false;
  }

  private static boolean isWhileLoop(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg, Loop loop) {
    if (loop.getLoopHeader().equals(loop.getLoopControl())) {
      boolean notWhileLoop = false;

      // If loopHeader and loopControl are the same, check if there are any other instructions
      // before Conditional Branch, if no, it is a while loop
      // For now it is simply check by instruction type
      // It should be checking `result` of the current instruction should be val1 of the
      // next instruction
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
        // TODO: this is a temporary change especially this one
        // to help identify if there are only instructions related with test
        if (inst instanceof SSAUnspecifiedExprInstruction) {
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
          return nextBBs.size() < 2;
        } else {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isDoLoop(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg, Loop loop) {
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
    return doLoop;
  }

  /**
   * Determine loop type based on what's in the loop
   *
   * @param cfg The control flow graph
   * @param loop The loop for type
   * @return The loop type
   */
  public static LoopType getLoopType(PrunedCFG<SSAInstruction, ISSABasicBlock> cfg, Loop loop) {
    if (loop.getLoopHeader().equals(loop.getLoopControl())) {
      // check if it's for loop
      // For now a for-loop refers PERFORM n TIMES
      if (isForLoop(loop)) return LoopType.FOR;

      // usually for loop will be detected as while loop too, so that check for-loop first
      if (isWhileLoop(cfg, loop)) return LoopType.WHILE;
    }

    // TODO: check unsupported loop types or add a loop type of ugly loop
    if (isDoLoop(cfg, loop)) return LoopType.DOWHILE;
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
   * Find out if the given chunk should be moved to translate with loop body One case is the chunk
   * is in the loop and before the conditional branch of the loop control The other case is the last
   * assignment in loop header/control before conditional
   *
   * @param cfg The control flow graph
   * @param chunk The instructions to be used to check
   * @param loops All the loops that's in the control flow graph
   * @return True if the given chunk is in the loop and before the conditional branch of the loop
   *     control
   */
  public static boolean shouldMoveAsLoopBody(
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
      // if it is loop control, conditional should be ignored
      if (isConditional(chunk)) {
        return false;
      }
      if (isAssignment(chunk)) {
        // if it is loop control, assignment should be ignored
        // except the last assignment for for-loop
        if (LoopType.FOR.equals(getLoopType(cfg, loop))) {
          List<SSAInstruction> controlInsts =
              IteratorUtil.streamify(currentBB.iterator()).collect(Collectors.toList());
          return chunk.contains(controlInsts.get(controlInsts.size() - 2));
        }
        return false;
      }
      return true;
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

  /**
   * In some cases operations on test can be merged, e.g. while loop In other cases these operations
   * should be separated
   *
   * @return True if it's conditional branch of a while loop
   */
  public static boolean shouldMergeTest(
      PrunedCFG<SSAInstruction, ISSABasicBlock> cfg,
      SSAInstruction inst,
      Map<ISSABasicBlock, Loop> loops) {
    if ((inst instanceof SSAConditionalBranchInstruction)) {
      Loop loop = getLoopByInstruction(cfg, inst, loops);
      return loop != null
          && loop.getLoopControl().equals(cfg.getBlockForInstruction(inst.iIndex()))
          && LoopType.WHILE.equals(getLoopType(cfg, loop));
    }
    return false;
  }
}
