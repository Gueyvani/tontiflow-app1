package com.tontiflow.application.service;

import com.tontiflow.domain.model.UserProfile;
import com.tontiflow.infrastructure.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    private UserProfileService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new UserProfileService(userProfileRepository);
    }

    @Test
    void getProfile_whenAbsent_throwsIllegalArgumentException() {
        UUID userId = UUID.randomUUID();
        when(userProfileRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(userId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getProfile_whenPresent_returnsIt() {
        UUID userId = UUID.randomUUID();
        UserProfile existing = new UserProfile(userId, "Alice Diop", "+221771234567");
        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(existing));

        UserProfile result = service.getProfile(userId);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void upsertProfile_whenAbsent_createsNewProfile() {
        UUID userId = UUID.randomUUID();
        when(userProfileRepository.findById(userId)).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile result = service.upsertProfile(userId, "Alice Diop", "+221771234567");

        assertThat(result.getId()).isEqualTo(userId);
        assertThat(result.getFullName()).isEqualTo("Alice Diop");
        assertThat(result.getPhoneNumber()).isEqualTo("+221771234567");
        verify(userProfileRepository).save(any(UserProfile.class));
    }

    @Test
    void upsertProfile_whenPresent_replacesExistingFields() {
        UUID userId = UUID.randomUUID();
        UserProfile existing = new UserProfile(userId, "Old Name", "+221700000000");
        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile result = service.upsertProfile(userId, "New Name", null);

        assertThat(result).isSameAs(existing);
        assertThat(result.getFullName()).isEqualTo("New Name");
        assertThat(result.getPhoneNumber()).isNull();
    }
}
