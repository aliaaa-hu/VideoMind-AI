export const DEMO_ITEM = {
  id: 1001,
  filename: 'Data Structures · Binary Tree Traversal.mp4',
  status: 'COMPLETED',
  uploadTime: '2026-07-10T14:30:00',
  transcriptText: 'This lesson introduces preorder, inorder, and postorder traversal, then compares recursive and iterative implementations.'
}

export const DEMO_PLAN = {
  understoodGoal: 'Understand the core lesson, extract key takeaways, and provide timestamped evidence with next steps.',
  tasks: ['Identify chapters and central concepts', 'Combine ASR and key-frame OCR evidence', 'Summarize findings and validate them with the Critic']
}

export const DEMO_TRACE = {
  stageDurationMs: {
    VIDEO_CONTEXT: 12840,
    RETRIEVAL: 860,
    PLANNER: 1150,
    EXECUTOR: 3420,
    CRITIC: 1260
  }
}

export const DEMO_EVALUATION = {
  structuredValid: true,
  evidenceSupportRate: 0.92,
  criticPassed: true
}

export const DEMO_RESULT = `## Binary Tree Traversal Analysis

## Key takeaways
- Preorder traversal follows “root, left subtree, right subtree” and is useful for cloning tree structures.
- Inorder traversal of a binary search tree yields an ordered sequence.
- Recursion is more direct; iteration keeps traversal state explicitly on a stack.

## Video evidence
- [02:05] ASR: The instructor introduces the preorder visit sequence.
- [02:08] OCR: The slide shows “root → left subtree → right subtree”.
- [08:42] ASR + OCR: The lesson compares the recursive call stack with an explicit stack.

## Suggested practice
- Trace all three traversal orders on the same tree by hand.
- Implement both versions and focus on the state kept on the stack.`
