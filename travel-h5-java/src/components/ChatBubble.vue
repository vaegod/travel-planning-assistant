<template>
  <div class="chat-bubble" :class="messageClass">
    <div class="bubble-content">
      <div class="message-text" v-if="message.role === 'user'">{{ message.content }}</div>
      <div class="message-text ai-message" v-else>
        <template v-if="message.content">{{ message.content }}</template>
      </div>
    </div>
    <div class="message-time" v-if="showTime">{{ formatTime }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

const messageClass = computed(() => {
  return props.message.role === 'user' ? 'user-message' : 'ai-message'
})

const showTime = computed(() => {
  return props.message.timestamp && props.message.content
})

const formatTime = computed(() => {
  if (!props.message.timestamp) return ''
  const date = new Date(props.message.timestamp)
  return `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
})
</script>

<style scoped>
.chat-bubble {
  display: flex;
  flex-direction: column;
  max-width: 86%;
}

.user-message {
  align-self: flex-end;
  align-items: flex-end;
}

.ai-message {
  align-self: flex-start;
  align-items: flex-start;
}

.bubble-content {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.chat-bubble.user-message .bubble-content {
  background: var(--primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.chat-bubble.ai-message .bubble-content {
  background: white;
  border: 1px solid var(--line);
  color: #323233;
  border-bottom-left-radius: 4px;
}

.message-time {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  padding: 0 4px;
}

.typing {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>