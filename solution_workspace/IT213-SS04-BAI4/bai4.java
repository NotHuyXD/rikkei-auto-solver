package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AiEtlService {

    private final ChatClient chatClient;

    public AiEtlService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public record ExtractedInformation(
            String fullName,
            String email,
            String phoneNumber,
            String address,
            String educationLevel,
            List<String> keySkills
    ) {}

    public ExtractedInformation extractCandidateInfo(String rawCvText) {
        String promptTemplate = """
                Hãy phân tích kỹ và trích xuất các thông tin cần thiết từ văn bản CV thô dưới đây.
                Văn bản CV thô:
                {rawCvText}
                """;

        return chatClient.prompt()
                .user(u -> u.text(promptTemplate).param("rawCvText", rawCvText))
                .call()
                .entity(ExtractedInformation.class);
    }
}